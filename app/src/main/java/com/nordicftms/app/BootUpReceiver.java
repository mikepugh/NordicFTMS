package com.nordicftms.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts FTMSService automatically on device boot.
 * No user interaction required — the BLE FTMS server starts silently in the background.
 */
public class BootUpReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "NordicFTMS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(LOG_TAG, "Boot completed — starting FTMSService");
            Intent ftmsIntent = new Intent(context, FTMSService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(ftmsIntent);
            } else {
                context.startService(ftmsIntent);
            }
        }
    }
}
