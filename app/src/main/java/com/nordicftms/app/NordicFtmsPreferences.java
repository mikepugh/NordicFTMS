package com.nordicftms.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class NordicFtmsPreferences {
    static final String PREFS_NAME = "nordicftms";
    static final String PREF_BLUETOOTH_PERMISSION_REQUESTED = "bluetooth_permission_requested";
    static final String PREF_DETAILED_TRACING_ENABLED = "detailed_tracing_enabled";
    static final String PREF_KICKR_RUN_MODE_ENABLED = "kickr_run_mode_enabled";

    public static final boolean DEFAULT_DETAILED_TRACING_ENABLED = false;
    public static final boolean DEFAULT_KICKR_RUN_MODE_ENABLED = true;

    private NordicFtmsPreferences() {
    }

    public static boolean isDetailedTracingEnabled(Context context) {
        return getSharedPreferences(context).getBoolean(
                PREF_DETAILED_TRACING_ENABLED,
                DEFAULT_DETAILED_TRACING_ENABLED
        );
    }

    public static void setDetailedTracingEnabled(Context context, boolean enabled) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(PREF_DETAILED_TRACING_ENABLED, enabled)
                .apply();
        syncStatusSnapshot(context);
    }

    public static boolean isKickrRunModeEnabled(Context context) {
        return getSharedPreferences(context).getBoolean(
                PREF_KICKR_RUN_MODE_ENABLED,
                DEFAULT_KICKR_RUN_MODE_ENABLED
        );
    }

    public static void setKickrRunModeEnabled(Context context, boolean enabled) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(PREF_KICKR_RUN_MODE_ENABLED, enabled)
                .apply();
        syncStatusSnapshot(context);
    }

    public static boolean wasBluetoothPermissionRequested(Context context) {
        return getSharedPreferences(context).getBoolean(PREF_BLUETOOTH_PERMISSION_REQUESTED, false);
    }

    public static void setBluetoothPermissionRequested(Context context, boolean requested) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(PREF_BLUETOOTH_PERMISSION_REQUESTED, requested)
                .apply();
    }

    public static void syncStatusSnapshot(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NordicFtmsStatusStore.getInstance().update(snapshot -> {
            snapshot.detailedTracingEnabled = isDetailedTracingEnabled(appContext);
            snapshot.kickrRunModeEnabled = isKickrRunModeEnabled(appContext);
        });
    }

    static SharedPreferences getSharedPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
