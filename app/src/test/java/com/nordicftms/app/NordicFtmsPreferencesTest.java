package com.nordicftms.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NordicFtmsPreferencesTest {
    @Test
    public void usesExpectedPreferenceDefaults() {
        assertFalse(NordicFtmsPreferences.DEFAULT_DETAILED_TRACING_ENABLED);
        assertTrue(NordicFtmsPreferences.DEFAULT_KICKR_RUN_MODE_ENABLED);
    }
}
