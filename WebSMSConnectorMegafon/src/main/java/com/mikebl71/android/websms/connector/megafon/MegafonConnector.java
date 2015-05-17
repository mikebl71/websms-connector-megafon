package com.mikebl71.android.websms.connector.megafon;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.widget.Toast;

import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;
import de.ub0r.android.websms.connector.common.Log;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.WebSMSException;
import de.ub0r.android.websms.connector.common.WebSMSNoNetworkException;

/**
 * Main class for Megafon Connector.
 * Receives commands from WebSMS and acts upon them.
 */
public class MegafonConnector extends Connector {

    // Logging tag
    public static final String TAG = "megafon";

    // Timeout for waiting a captcha answer from the user
    private static final long CAPTCHA_ANSWER_TIMEOUT_MS = 300000;

    // Delay between status checks
    private static final long STATUS_CHECK_DELAY_MS  = 10000;
    // Max number of status checks
    private static final int STATUS_CHECK_MAXCNT = 3;

    private static final String SMS_STATUS_ACCEPTED  = "0";
    private static final String SMS_STATUS_ENQUEUED  = "10";
    private static final String SMS_STATUS_SENT      = "20";
    private static final String SMS_STATUS_DELIVERED = "30";
    private static final String SMS_STATUS_FAILED    = "-1";

    // Sync object for solving a captcha
    private static final Object CAPTCHA_SYNC = new Object();
    // Captcha answer provided by the user
    private static String receivedCaptchaAnswer;

    private static class CaptchaInfo {
        private String challenge;
        private Bitmap image;
        private String answer;
    }


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
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(Preferences.PREFS_ENABLED, false)) {
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

        // retrieve megafon captcha info
        HttpResponse response = executeHttp(context,
                "http://moscow.megafon.ru/api/sms/captcha",
                null, "json",
                "http://moscow.megafon.ru/help/info/message/");

        String responseText = Utils.stream2str(response.getEntity().getContent());

        if (!responseText.contains("\"type\":\"recaptcha\"")) {
            Log.e(TAG, "Unexpected megafon captcha response: " + responseText);
            throw new WebSMSException(context, R.string.error_unexpected_megafon_captcha_info);
        }

        String captchaKey = getStringBetween(responseText, "\"pub\":\"", "\"");
        if (TextUtils.isEmpty(captchaKey)) {
            Log.e(TAG, "Unexpected megafon captcha response: " + responseText);
            throw new WebSMSException(context, R.string.error_unexpected_megafon_captcha_info);
        }

        // retrieve google captcha info
        response = executeHttp(context,
                "http://www.google.com/recaptcha/api/challenge?k=" + captchaKey + "&ajax=1&cachestop=0.08925109167881884",
                null, "json",
                "http://moscow.megafon.ru/help/info/message/");

        responseText = Utils.stream2str(response.getEntity().getContent());

        captchaInfo.challenge = getStringBetween(responseText, "challenge : '", "'");
        if (TextUtils.isEmpty(captchaInfo.challenge)) {
            Log.e(TAG, "Unexpected google challenge response: " + responseText);
            throw new WebSMSException(context, R.string.error_unexpected_google_captcha_info);
        }

        // reload (captcha is easier after reload)
        response = executeHttp(context,
                "http://www.google.com/recaptcha/api/reload?c=" + captchaInfo.challenge
                        + "&k=" + captchaKey
                        + "&reason=i&type=image&lang=en-GB",
                null, "json",
                "http://moscow.megafon.ru/help/info/message/");

        responseText = Utils.stream2str(response.getEntity().getContent());

        captchaInfo.challenge = getStringBetween(responseText, "Recaptcha.finish_reload('", "'");
        if (TextUtils.isEmpty(captchaInfo.challenge)) {
            Log.e(TAG, "Unexpected google reload response: " + responseText);
            throw new WebSMSException(context, R.string.error_unexpected_google_captcha_info);
        }

        // retrieve captcha image
        response = executeHttp(context,
                "https://www.google.com/recaptcha/api/image?c=" + captchaInfo.challenge,
                null, "image",
                "http://moscow.megafon.ru/help/info/message/");

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
                CAPTCHA_SYNC.wait(CAPTCHA_ANSWER_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
        }

        if (TextUtils.isEmpty(receivedCaptchaAnswer)) {
            throw new WebSMSException(context, R.string.error_no_captcha_answer);
        }

        captchaInfo.answer = receivedCaptchaAnswer;
    }

    private void sendMessage(Context context, ConnectorCommand command, CaptchaInfo captchaInfo) throws IOException {
        // send message text
        List<BasicNameValuePair> postData = new ArrayList<BasicNameValuePair>();
        postData.add(new BasicNameValuePair("addr", getRecipient(context, command)));
        postData.add(new BasicNameValuePair("message", command.getText()));
        postData.add(new BasicNameValuePair("recaptcha_challenge_field", captchaInfo.challenge));
        postData.add(new BasicNameValuePair("recaptcha_response_field", captchaInfo.answer));

        HttpResponse response = executeHttp(context,
                "http://moscow.megafon.ru/api/sms/send",
                postData, "json",
                "http://moscow.megafon.ru/help/info/message/");

        String responseText = Utils.stream2str(response.getEntity().getContent());

        String uniqueKey = getStringBetween(responseText, "\"unique_key\":\"", "\"");
        if (TextUtils.isEmpty(uniqueKey)) {
            if (responseText.contains("{\"errors\":[\"captcha\"]}")) {
                throw new WebSMSException(context, R.string.error_wrong_captcha);
            } else {
                Log.e(TAG, "Unexpected send response: " + responseText);
                throw new WebSMSException(context, R.string.error_unexpected_send_response);
            }
        }
        Log.d(TAG, "Message unique key: " + uniqueKey);

        // retrieve status
        String status = "";
        for (int tryCnt = 0; tryCnt < STATUS_CHECK_MAXCNT; tryCnt++) {

            sleep(STATUS_CHECK_DELAY_MS);

            postData = new ArrayList<BasicNameValuePair>();
            postData.add(new BasicNameValuePair("unique_key", uniqueKey));

            response = executeHttp(context,
                    "http://moscow.megafon.ru/api/sms/status",
                    postData, "json",
                    "http://moscow.megafon.ru/help/info/message/");

            responseText = Utils.stream2str(response.getEntity().getContent());

            status = getStringBetween(responseText, "\"status\":\"", "\"");

            if (TextUtils.isEmpty(status)) {
                Log.e(TAG, "Unexpected status response: " + responseText);
                throw new WebSMSException(context, R.string.error_unexpected_status_response);

            } else if (status.equals(SMS_STATUS_ACCEPTED)
                    || status.equals(SMS_STATUS_ENQUEUED)
                    || status.equals(SMS_STATUS_SENT)) {
                continue;

            } else if (status.equals(SMS_STATUS_DELIVERED)) {
                break;

            } else if (status.equals(SMS_STATUS_FAILED)) {
                Log.e(TAG, "Send failure: " + responseText);
                throw new WebSMSException(context, R.string.error_sms_failed);

            } else {
                Log.e(TAG, "Unexpected status response: " + responseText);
                throw new WebSMSException(context.getString(R.string.error_unexpected_sms_status, status));
            }
        }

        if (status.equals(SMS_STATUS_ACCEPTED)) {
            showToast(context, R.string.toast_sms_accepted);
        } else if (status.equals(SMS_STATUS_ENQUEUED)) {
            showToast(context, R.string.toast_sms_enqueued);
        } else if (status.equals(SMS_STATUS_SENT)) {
            showToast(context, R.string.toast_sms_sent);
        } else if (status.equals(SMS_STATUS_DELIVERED)) {
            showToast(context, R.string.toast_sms_delivered);
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

    private void showToast(final Context ctx, final int stringRes) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                Toast.makeText(ctx, ctx.getString(stringRes), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (Exception ex) {
        }
    }

    private HttpResponse executeHttp(Context context, String url, List<BasicNameValuePair> postPairs,
                                     String type, String referrer)
            throws IOException {

        //Utils.setVerboseLog(true);
        //java.util.logging.Logger.getLogger("org.apache.http.wire").setLevel(java.util.logging.Level.FINEST);
        //  adb shell setprop log.tag.org.apache.http.wire VERBOSE

        Utils.HttpOptions options = new Utils.HttpOptions("UTF-8");
        options.url = url;
        options.userAgent = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:35.0) Gecko/20100101 Firefox/35.0";
        options.referer = referrer;
        options.trustAll = true;

        options.headers = new ArrayList<Header>();
        options.headers.add(new BasicHeader("Accept-Language", "en-US,en;q=0.5"));

        if (postPairs != null) {
            options.addFormParameter(postPairs);
            options.headers.add(new BasicHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"));
        }

        if (type.equals("json")) {
            options.headers.add(new BasicHeader("Accept", "application/json, text/javascript, */*; q=0.01"));
            options.headers.add(new BasicHeader("X-Requested-With", "XMLHttpRequest"));
        }

        HttpResponse response = Utils.getHttpClient(options);

        if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
            throw new WebSMSException(context.getString(R.string.error_http, response.getStatusLine().getReasonPhrase()));
        }

        if (response.getEntity() == null) {
            throw new WebSMSException(context, R.string.error_empty_response);
        }

        return response;
    }
}
