package com.nordicftms.app;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Minimal launcher activity for NordicFTMS.
 *
 * On launch, starts the FTMSService (BLE FTMS server backed by gRPC)
 * and immediately moves to the background so iFit can take focus.
 * The user never needs to interact with this UI — everything is automatic.
 */
public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "NordicFTMS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Auto-start the FTMS BLE server
        Intent ftmsIntent = new Intent(getApplicationContext(), FTMSService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(ftmsIntent);
        } else {
            startService(ftmsIntent);
        }
        Log.i(LOG_TAG, "Auto-started FTMSService");

        // Move to background so iFit can take focus
        if (savedInstanceState == null) {
            moveTaskToBack(true);
        }
    }
}
