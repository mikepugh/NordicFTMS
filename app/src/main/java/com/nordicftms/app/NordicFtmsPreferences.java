package com.nordicftms.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

public final class NordicFtmsPreferences {
    static final String PREFS_NAME = "nordicftms";
    static final String PREF_BLUETOOTH_PERMISSION_REQUESTED = "bluetooth_permission_requested";
    static final String PREF_DETAILED_TRACING_ENABLED = "detailed_tracing_enabled";
    static final String PREF_DETAILED_TRACING_EXPIRES_AT_MS = "detailed_tracing_expires_at_ms";
    static final String PREF_KICKR_RUN_MODE_ENABLED = "kickr_run_mode_enabled";

    public static final boolean DEFAULT_DETAILED_TRACING_ENABLED = false;
    public static final boolean DEFAULT_KICKR_RUN_MODE_ENABLED = true;

    private static final DetailedTracingSession tracingSession = new DetailedTracingSession();
    private static Handler expiryHandler;
    private static Runnable expiryCallback;

    private NordicFtmsPreferences() {
    }

    public static synchronized boolean isDetailedTracingEnabled(Context context) {
        SharedPreferences preferences = getSharedPreferences(context);
        if (!preferences.getBoolean(PREF_DETAILED_TRACING_ENABLED, DEFAULT_DETAILED_TRACING_ENABLED)) {
            tracingSession.clear();
            return false;
        }
        if (tracingSession.remainingMs(
                preferences.getLong(PREF_DETAILED_TRACING_EXPIRES_AT_MS, 0L),
                System.currentTimeMillis(), SystemClock.elapsedRealtime()) > 0) {
            return true;
        }
        preferences.edit().putBoolean(PREF_DETAILED_TRACING_ENABLED, false)
                .remove(PREF_DETAILED_TRACING_EXPIRES_AT_MS).apply();
        tracingSession.clear();
        return false;
    }

    public static void setDetailedTracingEnabled(Context context, boolean enabled) {
        synchronized (NordicFtmsPreferences.class) {
            SharedPreferences.Editor editor = getSharedPreferences(context).edit()
                    .putBoolean(PREF_DETAILED_TRACING_ENABLED, enabled);
            tracingSession.clear();
            if (enabled) {
                editor.putLong(PREF_DETAILED_TRACING_EXPIRES_AT_MS,
                        System.currentTimeMillis() + DetailedTracingSession.DURATION_MS);
            } else {
                editor.remove(PREF_DETAILED_TRACING_EXPIRES_AT_MS);
            }
            editor.apply();
            scheduleTracingExpiry(context);
        }
        syncStatusSnapshot(context);
    }

    static synchronized void initializeDetailedTracing(Context context) {
        scheduleTracingExpiry(context);
    }

    private static synchronized void scheduleTracingExpiry(Context context) {
        if (expiryHandler == null) expiryHandler = new Handler(Looper.getMainLooper());
        if (expiryCallback != null) expiryHandler.removeCallbacks(expiryCallback);
        if (!isDetailedTracingEnabled(context)) return;
        Context app = context.getApplicationContext();
        expiryCallback = () -> {
            syncStatusSnapshot(app);
            scheduleTracingExpiry(app);
        };
        long remainingMs = tracingSession.remainingMs(
                getSharedPreferences(app).getLong(PREF_DETAILED_TRACING_EXPIRES_AT_MS, 0L),
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
        // Handler uses uptime; recheck elapsed time after sleep or a wall-clock change.
        expiryHandler.postDelayed(expiryCallback, Math.min(remainingMs, 60_000L));
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
