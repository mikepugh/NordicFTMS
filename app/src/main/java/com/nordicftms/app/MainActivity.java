package com.nordicftms.app;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * Minimal launcher activity for NordicFTMS.
 *
 * On launch, requests any required Bluetooth runtime permissions, starts the
 * FTMS service once the app is allowed to advertise over BLE, and then moves
 * to the background so iFit can take focus.
 */
public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "NordicFTMS";
    private static final String PREFS_NAME = "nordicftms";
    private static final String PREF_BLUETOOTH_PERMISSION_REQUESTED = "bluetooth_permission_requested";
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1001;

    private TextView statusTextView;
    private TextView detailTextView;
    private Button grantPermissionButton;
    private Button openSettingsButton;
    private boolean bluetoothPermissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.status_text);
        detailTextView = findViewById(R.id.detail_text);
        grantPermissionButton = findViewById(R.id.grant_permission_button);
        openSettingsButton = findViewById(R.id.open_settings_button);

        bluetoothPermissionRequested = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_BLUETOOTH_PERMISSION_REQUESTED, false);

        grantPermissionButton.setOnClickListener(view -> requestBluetoothPermissions());
        openSettingsButton.setOnClickListener(view -> openAppSettings());

        renderLaunchState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLaunchState();

        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            startFtmsServiceAndBackground();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != BLUETOOTH_PERMISSION_REQUEST_CODE) {
            return;
        }

        renderLaunchState();
        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            startFtmsServiceAndBackground();
        }
    }

    private void renderLaunchState() {
        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            statusTextView.setText("Starting NordicFTMS...");
            detailTextView.setText("The FTMS and DIRCON services will keep running in the background.");
            grantPermissionButton.setVisibility(View.GONE);
            openSettingsButton.setVisibility(View.GONE);
            return;
        }

        boolean permanentlyDenied = isBluetoothPermissionPermanentlyDenied();
        String missingPermissions = describeMissingPermissionsForDisplay();

        statusTextView.setText("Bluetooth access is required before NordicFTMS can start.");
        if (permanentlyDenied) {
            detailTextView.setText("Android is no longer showing the permission dialog for "
                    + missingPermissions + ". Open app settings and allow Bluetooth access.");
            grantPermissionButton.setVisibility(View.GONE);
            openSettingsButton.setVisibility(View.VISIBLE);
        } else {
            detailTextView.setText("Tap the button below to allow " + missingPermissions
                    + ". NordicFTMS cannot advertise over BLE until Android grants access.");
            grantPermissionButton.setVisibility(View.VISIBLE);
            openSettingsButton.setVisibility(View.GONE);
        }
    }

    private void requestBluetoothPermissions() {
        if (!BluetoothPermissionGate.requiresRuntimePermissions()) {
            startFtmsServiceAndBackground();
            return;
        }

        bluetoothPermissionRequested = true;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BLUETOOTH_PERMISSION_REQUESTED, true)
                .apply();

        ActivityCompat.requestPermissions(
                this,
                BluetoothPermissionGate.getRequiredPermissions(),
                BLUETOOTH_PERMISSION_REQUEST_CODE
        );
    }

    private boolean isBluetoothPermissionPermanentlyDenied() {
        if (!BluetoothPermissionGate.requiresRuntimePermissions() || !bluetoothPermissionRequested) {
            return false;
        }

        for (String permission : BluetoothPermissionGate.getMissingPermissions(this)) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    private String describeMissingPermissionsForDisplay() {
        String[] missingPermissions = BluetoothPermissionGate.getMissingPermissions(this);
        if (missingPermissions.length == 0) {
            return "Bluetooth access";
        }

        StringBuilder description = new StringBuilder();
        for (int index = 0; index < missingPermissions.length; index++) {
            if (index > 0) {
                description.append(" and ");
            }
            description.append(BluetoothPermissionGate.permissionDisplayLabel(missingPermissions[index]));
        }
        return description.toString();
    }

    private void openAppSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void startFtmsServiceAndBackground() {
        Intent ftmsIntent = new Intent(getApplicationContext(), FTMSService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(ftmsIntent);
        } else {
            startService(ftmsIntent);
        }
        Log.i(LOG_TAG, "Started FTMSService after permission gate");

        moveTaskToBack(true);
    }
}
