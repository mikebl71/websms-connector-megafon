package com.mikebl71.android.websms.connector.megafon;

import android.os.Bundle;
import android.preference.PreferenceActivity;

/**
 * Preferences.
 */
@SuppressWarnings("deprecation")
public final class Preferences extends PreferenceActivity {

    public static final String PREFS_ENABLED = "enable_connector";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.prefs);
    }

}
