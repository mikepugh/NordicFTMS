package com.nordicftms.app;

import android.app.Application;

public final class NordicFtmsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SupportDiagnostics.initialize(this);
        NordicFtmsPreferences.syncStatusSnapshot(this);
        SupportDiagnostics.request(this, "app_start", false);
    }
}
