package com.mikebl71.android.websms.connector.megafon;

import android.os.Bundle;
import android.preference.PreferenceActivity;

/**
 * Preferences.
 */
public final class Preferences extends PreferenceActivity {

    /** Preference key: enabled. */
    static final String PREFS_ENABLED = "enable_connector";

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.addPreferencesFromResource(R.xml.prefs);
    }

}
