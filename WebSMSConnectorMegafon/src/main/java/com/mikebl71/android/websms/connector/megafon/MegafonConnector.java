package com.mikebl71.android.websms.connector.megafon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.impl.cookie.BasicClientCookie2;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import de.ub0r.android.websms.connector.common.CharacterTable;
import de.ub0r.android.websms.connector.common.CharacterTableSMSLengthCalculator;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;
import de.ub0r.android.websms.connector.common.Log;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.Utils.HttpOptions;
import de.ub0r.android.websms.connector.common.WebSMSException;
import de.ub0r.android.websms.connector.common.WebSMSNoNetworkException;

/**
 * Main class for Megafon Connector.
 * Receives commands from WebSMS and acts upon them.
 */
public class MegafonConnector extends Connector {

    // Logging tag
    private static final String TAG = "megafon";

    // URLs
    private static final String URL_HOME = "https://sendsms.megafon.ru/";
    private static final String URL_CAPTCHA_INFO = "http://www.google.com/recaptcha/api/noscript?lang=ru&k=6Lc7XMUSAAAAAALuekCTAzdT5U0zeiEUQbTRZIBu";
    private static final String URL_CAPTCHA_IMAGE_PREFIX = "http://www.google.com/recaptcha/api/image?c=";
    private static final String URL_SEND = "https://sendsms.megafon.ru/sms.action";

    // HTTP request properties
    private static final String ENCODING = "UTF-8";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/37.0.2062.120 Chrome/37.0.2062.120 Safari/537.36";

    // Timeout for waiting a captcha answer from the user
    private static final long CAPTCHA_ANSWER_TIMEOUT = 60000;

    // Sync object for solving a captcha
    private static final Object CAPTCHA_SYNC = new Object();
    // Captcha answer provided by the user
    private static String receivedCaptchaAnswer;

    private static class CaptchaInfo {
        private String challenge;
        private Bitmap image;
        private String answer;
    };


    /**
     * Initializes {@link ConnectorSpec}. This is only run once.
     * Changing properties are set in updateSpec().
     */
    @Override
    public ConnectorSpec initSpec(Context context) {
        ConnectorSpec c = new ConnectorSpec(context.getString(R.string.connector_megafon_name));
        c.setAuthor(context.getString(R.string.connector_megafon_author));
        c.setBalance(null);
        c.setLimitLength(150);
        c.setCapabilities(ConnectorSpec.CAPABILITIES_SEND
                | ConnectorSpec.CAPABILITIES_PREFS);

        c.addSubConnector(TAG, c.getName(), SubConnectorSpec.FEATURE_NONE);

        return c;
    }

    /**
     * Updates connector's status.
     */
    @Override
    public ConnectorSpec updateSpec(Context context, ConnectorSpec connectorSpec) {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (p.getBoolean(Preferences.PREFS_ENABLED, false)) {
            connectorSpec.setReady();
        } else {
            connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
        }
        return connectorSpec;
    }

    /**
     * Called if any broadcast with a solved captcha arrived.
     */
    @Override
    protected void gotSolvedCaptcha(Context context, String solvedCaptcha) {
        receivedCaptchaAnswer = solvedCaptcha;
        synchronized (CAPTCHA_SYNC) {
            CAPTCHA_SYNC.notify();
        }
    }

    /**
     * Called to send the actual message.
     */
    @Override
    protected void doSend(Context context, Intent intent) throws IOException {
        if (!Utils.isNetworkAvailable(context)) {
            throw new WebSMSNoNetworkException(context);
        }

        CaptchaInfo captchaInfo = retrieveCaptcha(context);

        solveCaptcha(context, captchaInfo);

        sendMessage(context, new ConnectorCommand(intent), captchaInfo);
    }

    private CaptchaInfo retrieveCaptcha(Context context) throws IOException {
        CaptchaInfo captchaInfo = new CaptchaInfo();

        // retrieve captcha info
        HttpResponse response = executeHttp(context, URL_CAPTCHA_INFO, null, URL_HOME);

        String responseText = Utils.stream2str(response.getEntity().getContent());

        captchaInfo.challenge = getStringBetween(responseText, "id=\"recaptcha_challenge_field\" value=\"", "\"");
        if (TextUtils.isEmpty(captchaInfo.challenge)) {
            throw new WebSMSException(context, R.string.error_unexpected_captcha_info);
        }

        // retrieve captcha image
        response = executeHttp(context, URL_CAPTCHA_IMAGE_PREFIX + captchaInfo.challenge, null, URL_HOME);

        HttpEntity responseEntity = response.getEntity();
        InputStream inputStream = null;
        try {
            inputStream = responseEntity.getContent();
            captchaInfo.image = BitmapFactory.decodeStream(inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
            responseEntity.consumeContent();
        }
        return captchaInfo;
    }

    private void solveCaptcha(Context context, CaptchaInfo captchaInfo) throws IOException {
        receivedCaptchaAnswer = null;

        Intent intent = new Intent(Connector.ACTION_CAPTCHA_REQUEST);
        intent.putExtra(Connector.EXTRA_CAPTCHA_DRAWABLE, captchaInfo.image);
        getSpec(context).setToIntent(intent);
        context.sendBroadcast(intent);

        try {
            synchronized (CAPTCHA_SYNC) {
                CAPTCHA_SYNC.wait(CAPTCHA_ANSWER_TIMEOUT);
            }
        } catch (InterruptedException e) {
        }

        if (TextUtils.isEmpty(receivedCaptchaAnswer)) {
            throw new WebSMSException(context, R.string.error_no_captcha_answer);
        }

        captchaInfo.answer = receivedCaptchaAnswer;
    }

    private void sendMessage(Context context, ConnectorCommand command, CaptchaInfo captchaInfo) throws IOException {
        List<BasicNameValuePair> postData = new ArrayList<BasicNameValuePair>();
        postData.add(new BasicNameValuePair("charcheck", "йцукен"));
        postData.add(new BasicNameValuePair("lang", ""));
        postData.add(new BasicNameValuePair("addr", getRecipient(context, command)));
        postData.add(new BasicNameValuePair("message", command.getText()));

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        postData.add(new BasicNameValuePair("send_day", String.format("%02d", cal.get(Calendar.DATE))));
        postData.add(new BasicNameValuePair("send_month", String.format("%02d", cal.get(Calendar.MONTH) + 1)));
        postData.add(new BasicNameValuePair("send_hour", Integer.toString(cal.get(Calendar.HOUR_OF_DAY))));
        postData.add(new BasicNameValuePair("send_minute", Integer.toString(cal.get(Calendar.MINUTE))));
        postData.add(new BasicNameValuePair("send_year", Integer.toString(cal.get(Calendar.YEAR))));

        postData.add(new BasicNameValuePair("recaptcha_challenge_field", captchaInfo.challenge));
        postData.add(new BasicNameValuePair("recaptcha_response_field", captchaInfo.answer));

        HttpResponse response = executeHttp(context, URL_SEND, postData, URL_HOME);

        String responseText = Utils.stream2str(response.getEntity().getContent());

        String error = getStringBetween(responseText, "<h1 class=\"error\">", "</h1>").trim();
        if (!TextUtils.isEmpty(error)) {
            throw new WebSMSException(context, R.string.error_send, error);
        }
        if (responseText.contains("link-check-status")) {
            // accepted by Megafon
        } else {
            throw new WebSMSException(context, R.string.error_send_unexpected);
        }
    }

    private String getRecipient(Context context, ConnectorCommand command) {
        if (command.getRecipients().length == 0) {
            throw new WebSMSException(context, R.string.error_no_rus_recipient);
        }
        String recipient = Utils.cleanRecipient(command.getRecipients()[0]);
        if (!recipient.startsWith("+7")) {
            throw new WebSMSException(context, R.string.error_no_rus_recipient);
        }
        recipient = recipient.substring(2);
        return recipient;
    }

    private String getStringBetween(String src, String from, String to) {
        String sub = src;
        if (sub != null) {
            int fromIdx = sub.indexOf(from);
            if (fromIdx >= 0) {
                sub = sub.substring(fromIdx + from.length());
                int toIdx = sub.indexOf(to);
                if (toIdx >= 0) {
                    sub = sub.substring(0, toIdx);
                    return sub;
                }
            }
        }
        return "";
    }

    private HttpResponse executeHttp(Context context, String url, List<BasicNameValuePair> postPairs, String referrer)
            throws IOException {
        HttpOptions options = new HttpOptions(ENCODING);
        options.url = url;
        options.userAgent = USER_AGENT;
        options.referer = referrer;
        options.trustAll = true;
        options.postData = null;

        if (postPairs != null) {
            MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);
            for (BasicNameValuePair nvp : postPairs) {
                try {
                    entity.addPart(nvp.getName(), new StringBody(nvp.getValue()));
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException();
                }
            }
            options.postData = entity;
        }

        // apparently, google returns easier captchas if a 'NID' cookie is present
        if (options.headers == null) {
            options.headers = new ArrayList<Header>();
        }
        options.headers.add(new BasicHeader("Cookie", "NID=50=RbHwrmdgEAl6v3XPDKfJey5zpW7n84oRvsTZOK0LuYwW0m0UDFcPmts2HqKaZc2-Rdo7iLsrYKOUVKV4ztyb7JMDWavDVmvsyC2UldBcyFKsmyM_4Qhr761WpGHfoZPZ"));

        HttpResponse response = Utils.getHttpClient(options);

        if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
            throw new WebSMSException(context,
                    R.string.error_http,
                    response.getStatusLine().getReasonPhrase());
        }

        if (response.getEntity() == null) {
            throw new WebSMSException(context, R.string.error_empty_response);
        }

        return response;
    }

}
