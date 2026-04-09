package com.nordicftms.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements NordicFtmsStatusStore.Listener {
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1001;
    private static final String POWERTREAD_URL = "https://powertread.fit";

    private final NordicFtmsLogger logger = NordicFtmsLogger.getInstance();

    private TextView statusTextView;
    private TextView detailTextView;
    private TextView serviceStateValue;
    private TextView permissionStateValue;
    private TextView backendStateValue;
    private TextView bleStateValue;
    private TextView dirconProfileValue;
    private TextView advertisedNameValue;
    private TextView dirconNameValue;
    private TextView reconnectAttemptValue;
    private TextView machineNameValue;
    private TextView machineTypeValue;
    private TextView firmwareValue;
    private TextView serialValue;
    private TextView speedRangeValue;
    private TextView inclineRangeValue;
    private TextView resistanceRangeValue;
    private TextView currentSpeedValue;
    private TextView currentInclineValue;
    private TextView currentDistanceValue;
    private TextView currentResistanceValue;
    private TextView currentCadenceValue;
    private TextView currentWattsValue;
    private TextView lastErrorValue;
    private Button grantPermissionButton;
    private Button openSettingsButton;
    private Button retryBackendButton;
    private Button restartBluetoothButton;
    private Button hideToBackgroundButton;
    private Button openPowerTreadButton;
    private SwitchMaterial detailedTracingSwitch;
    private SwitchMaterial kickrRunSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        wireActions();

        detailedTracingSwitch.setChecked(NordicFtmsPreferences.isDetailedTracingEnabled(this));
        kickrRunSwitch.setChecked(NordicFtmsPreferences.isKickrRunModeEnabled(this));

        renderSnapshot(NordicFtmsStatusStore.getInstance().getSnapshot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        NordicFtmsStatusStore.getInstance().addListener(this);
    }

    @Override
    protected void onStop() {
        NordicFtmsStatusStore.getInstance().removeListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            startFtmsService(false);
        }
        renderSnapshot(NordicFtmsStatusStore.getInstance().getSnapshot());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != BLUETOOTH_PERMISSION_REQUEST_CODE) {
            return;
        }

        renderSnapshot(NordicFtmsStatusStore.getInstance().getSnapshot());
        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            startFtmsService(false);
        }
    }

    @Override
    public void onSnapshotChanged(ServiceStatusSnapshot snapshot) {
        runOnUiThread(() -> renderSnapshot(snapshot));
    }

    private void bindViews() {
        statusTextView = findViewById(R.id.status_text);
        detailTextView = findViewById(R.id.detail_text);
        serviceStateValue = findViewById(R.id.service_state_value);
        permissionStateValue = findViewById(R.id.permission_state_value);
        backendStateValue = findViewById(R.id.backend_state_value);
        bleStateValue = findViewById(R.id.ble_state_value);
        dirconProfileValue = findViewById(R.id.dircon_profile_value);
        advertisedNameValue = findViewById(R.id.advertised_name_value);
        dirconNameValue = findViewById(R.id.dircon_name_value);
        reconnectAttemptValue = findViewById(R.id.reconnect_attempt_value);
        machineNameValue = findViewById(R.id.machine_name_value);
        machineTypeValue = findViewById(R.id.machine_type_value);
        firmwareValue = findViewById(R.id.firmware_value);
        serialValue = findViewById(R.id.serial_value);
        speedRangeValue = findViewById(R.id.speed_range_value);
        inclineRangeValue = findViewById(R.id.incline_range_value);
        resistanceRangeValue = findViewById(R.id.resistance_range_value);
        currentSpeedValue = findViewById(R.id.current_speed_value);
        currentInclineValue = findViewById(R.id.current_incline_value);
        currentDistanceValue = findViewById(R.id.current_distance_value);
        currentResistanceValue = findViewById(R.id.current_resistance_value);
        currentCadenceValue = findViewById(R.id.current_cadence_value);
        currentWattsValue = findViewById(R.id.current_watts_value);
        lastErrorValue = findViewById(R.id.last_error_value);
        grantPermissionButton = findViewById(R.id.grant_permission_button);
        openSettingsButton = findViewById(R.id.open_settings_button);
        retryBackendButton = findViewById(R.id.retry_backend_button);
        restartBluetoothButton = findViewById(R.id.restart_bluetooth_button);
        hideToBackgroundButton = findViewById(R.id.hide_to_background_button);
        openPowerTreadButton = findViewById(R.id.open_powertread_button);
        detailedTracingSwitch = findViewById(R.id.detailed_tracing_switch);
        kickrRunSwitch = findViewById(R.id.kickr_run_switch);
    }

    private void wireActions() {
        grantPermissionButton.setOnClickListener(view -> requestBluetoothPermissions());
        openSettingsButton.setOnClickListener(view -> openAppSettings());
        retryBackendButton.setOnClickListener(view -> sendServiceAction(FTMSService.ACTION_RETRY_BACKEND));
        restartBluetoothButton.setOnClickListener(view -> sendServiceAction(FTMSService.ACTION_RESTART_BLUETOOTH));
        hideToBackgroundButton.setOnClickListener(view -> moveTaskToBack(true));
        openPowerTreadButton.setOnClickListener(view -> openPowerTreadLink());

        detailedTracingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) {
                return;
            }
            NordicFtmsPreferences.setDetailedTracingEnabled(this, isChecked);
            logger.info(this, "ui_toggle_detailed_tracing", "Detailed tracing set to " + isChecked);
            sendServiceAction(FTMSService.ACTION_PREFERENCES_CHANGED);
        });

        kickrRunSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) {
                return;
            }
            NordicFtmsPreferences.setKickrRunModeEnabled(this, isChecked);
            logger.info(this, "ui_toggle_kickr_run", "Kickr Run mode set to " + isChecked);
            sendServiceAction(FTMSService.ACTION_PREFERENCES_CHANGED);
        });
    }

    private void renderSnapshot(ServiceStatusSnapshot snapshot) {
        boolean hasPermissions = BluetoothPermissionGate.hasRequiredPermissions(this);
        boolean permanentlyDenied = isBluetoothPermissionPermanentlyDenied();
        String missingPermissions = describeMissingPermissionsForDisplay();
        boolean healthy = isRuntimeHealthy(snapshot);

        statusTextView.setText(buildPrimaryStatus(snapshot, hasPermissions));
        detailTextView.setText(buildDetailStatus(snapshot, hasPermissions, permanentlyDenied, missingPermissions));

        serviceStateValue.setText("Service state: " + humanize(snapshot.serviceState.name()));
        permissionStateValue.setText("Bluetooth permissions: " + humanize(snapshot.bluetoothPermissionState.name()));
        backendStateValue.setText("Backend: " + humanize(snapshot.backendState.name()));
        bleStateValue.setText("Bluetooth peripheral: " + humanize(snapshot.bleState.name()));
        dirconProfileValue.setText("DIRCON profile: " + humanize(snapshot.dirconProfile.name()));
        advertisedNameValue.setText("FTMS name: " + snapshot.advertisedFtmsName);
        dirconNameValue.setText("DIRCON name: " + snapshot.dirconServiceName);
        reconnectAttemptValue.setText("Reconnect attempt: " + snapshot.reconnectAttempt);

        machineNameValue.setText("Machine: " + snapshot.consoleSummary.machineName);
        machineTypeValue.setText("Machine type: " + snapshot.consoleSummary.machineType);
        firmwareValue.setText("Firmware: " + snapshot.consoleSummary.firmwareVersion);
        serialValue.setText("Serial: " + snapshot.consoleSummary.serialNumber);
        speedRangeValue.setText("Speed range: " + formatRange(snapshot.consoleSummary.minSpeedKph, snapshot.consoleSummary.maxSpeedKph, "kph"));
        inclineRangeValue.setText("Incline range: " + formatRange(snapshot.consoleSummary.minInclinePercent, snapshot.consoleSummary.maxInclinePercent, "%"));
        resistanceRangeValue.setText("Resistance range: " + formatRange(snapshot.consoleSummary.minResistance, snapshot.consoleSummary.maxResistance, ""));

        currentSpeedValue.setText("Current speed: " + formatValue(snapshot.liveMetrics.speedKph, "kph"));
        currentInclineValue.setText("Current incline: " + formatValue(snapshot.liveMetrics.inclinePercent, "%"));
        currentDistanceValue.setText("Distance: " + formatValue(snapshot.liveMetrics.distanceKm, "km"));
        currentResistanceValue.setText("Resistance: " + formatValue(snapshot.liveMetrics.resistance, ""));
        currentCadenceValue.setText("Cadence: " + formatValue(snapshot.liveMetrics.cadenceRpm, "rpm"));
        currentWattsValue.setText("Watts: " + formatValue(snapshot.liveMetrics.watts, "W"));

        if (snapshot.lastError.hasValue() && !healthy) {
            String timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(snapshot.lastError.timestampMs));
            lastErrorValue.setText("Last error: [" + snapshot.lastError.level + "] "
                    + snapshot.lastError.eventName + " at " + timestamp + " - " + snapshot.lastError.message);
        } else {
            lastErrorValue.setText("Last error: none");
        }

        if (detailedTracingSwitch.isChecked() != snapshot.detailedTracingEnabled) {
            detailedTracingSwitch.setChecked(snapshot.detailedTracingEnabled);
        }
        if (kickrRunSwitch.isChecked() != snapshot.kickrRunModeEnabled) {
            kickrRunSwitch.setChecked(snapshot.kickrRunModeEnabled);
        }
        kickrRunSwitch.setEnabled(isKickrRunEligible(snapshot));

        grantPermissionButton.setEnabled(!hasPermissions);
        grantPermissionButton.setVisibility(hasPermissions || permanentlyDenied ? Button.GONE : Button.VISIBLE);
        openSettingsButton.setVisibility(permanentlyDenied ? Button.VISIBLE : Button.GONE);
    }

    private String buildPrimaryStatus(ServiceStatusSnapshot snapshot, boolean hasPermissions) {
        if (!hasPermissions) {
            return "Bluetooth access is required before NordicFTMS can start.";
        }
        if (snapshot.bleState == ServiceStatusSnapshot.BleState.ERROR) {
            return "Bluetooth peripheral needs attention.";
        }
        if (snapshot.backendState == ServiceStatusSnapshot.BackendState.RETRYING) {
            return "Trying to reconnect to the treadmill backend.";
        }
        if (snapshot.backendState == ServiceStatusSnapshot.BackendState.CONNECTING) {
            return "Connecting to the treadmill backend.";
        }
        if (snapshot.serviceState == ServiceStartupState.RUNNING) {
            return "NordicFTMS is active and publishing live status.";
        }
        return "NordicFTMS is waiting to start.";
    }

    private String buildDetailStatus(
            ServiceStatusSnapshot snapshot,
            boolean hasPermissions,
            boolean permanentlyDenied,
            String missingPermissions
    ) {
        if (!hasPermissions) {
            if (permanentlyDenied) {
                return "Android is no longer showing the permission dialog for " + missingPermissions
                        + ". Open app settings and allow Bluetooth access.";
            }
            return "Tap Grant Bluetooth Access to allow " + missingPermissions
                    + ". NordicFTMS cannot expose its FTMS peripheral until Android grants access.";
        }
        if (isRuntimeHealthy(snapshot)) {
            return "Bluetooth, GlassOS, and DIRCON status are shown below in real time.";
        }
        if (snapshot.lastError.hasValue()) {
            return snapshot.lastError.message;
        }
        if (snapshot.serviceState == ServiceStartupState.RUNNING) {
            return "NordicFTMS is running and waiting for the backend or BLE stack to settle.";
        }
        return "Use Retry Backend or Restart Bluetooth Peripheral if the treadmill backend or BLE stack needs a nudge.";
    }

    private boolean isRuntimeHealthy(ServiceStatusSnapshot snapshot) {
        return snapshot != null
                && snapshot.serviceState == ServiceStartupState.RUNNING
                && snapshot.backendState == ServiceStatusSnapshot.BackendState.READY
                && snapshot.bleState == ServiceStatusSnapshot.BleState.ACTIVE;
    }

    private void requestBluetoothPermissions() {
        if (!BluetoothPermissionGate.requiresRuntimePermissions()) {
            startFtmsService(false);
            return;
        }

        NordicFtmsPreferences.setBluetoothPermissionRequested(this, true);
        ActivityCompat.requestPermissions(
                this,
                BluetoothPermissionGate.getRequiredPermissions(),
                BLUETOOTH_PERMISSION_REQUEST_CODE
        );
    }

    private boolean isBluetoothPermissionPermanentlyDenied() {
        if (!BluetoothPermissionGate.requiresRuntimePermissions()
                || !NordicFtmsPreferences.wasBluetoothPermissionRequested(this)) {
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

    private void openPowerTreadLink() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(POWERTREAD_URL));
            startActivity(intent);
        } catch (Exception error) {
            logger.error(this, "open_powertread_link_error", "Unable to open " + POWERTREAD_URL, error);
        }
    }

    private void startFtmsService(boolean moveToBackground) {
        Intent ftmsIntent = new Intent(getApplicationContext(), FTMSService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(ftmsIntent);
        } else {
            startService(ftmsIntent);
        }
        logger.info(this, "ui_start_service", "Started FTMSService from the console");

        if (moveToBackground) {
            moveTaskToBack(true);
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(getApplicationContext(), FTMSService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean isKickrRunEligible(ServiceStatusSnapshot snapshot) {
        String machineType = snapshot.consoleSummary.machineType == null
                ? ""
                : snapshot.consoleSummary.machineType.toUpperCase(Locale.US);
        return machineType.contains("TREADMILL")
                || machineType.contains("INCLINE")
                || (snapshot.consoleSummary.canSetSpeed && snapshot.consoleSummary.canSetIncline);
    }

    private String humanize(String enumName) {
        return enumName.toLowerCase(Locale.US).replace('_', ' ');
    }

    private String formatRange(double minValue, double maxValue, String unit) {
        return formatNumber(minValue) + " - " + formatNumber(maxValue) + (unit.isEmpty() ? "" : " " + unit);
    }

    private String formatValue(double value, String unit) {
        return formatNumber(value) + (unit.isEmpty() ? "" : " " + unit);
    }

    private String formatNumber(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
