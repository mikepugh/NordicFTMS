package com.nordicftms.app;

import com.ifit.glassos.ConsoleInfo;

public final class ServiceStatusSnapshot {
    public enum BluetoothPermissionState {
        UNKNOWN,
        GRANTED,
        MISSING
    }

    public enum BleState {
        STOPPED,
        STARTING,
        ACTIVE,
        WAITING_FOR_PERMISSION,
        ERROR
    }

    public enum BackendState {
        DISCONNECTED,
        CONNECTING,
        READY,
        RETRYING,
        ERROR
    }

    public enum DirconProfile {
        DISABLED,
        GENERIC,
        KICKR_RUN
    }

    public static final class ConsoleSummary {
        public String machineName = "Unknown";
        public String machineType = "Unknown";
        public String firmwareVersion = "Unknown";
        public String serialNumber = "Unknown";
        public double minSpeedKph = 0.0;
        public double maxSpeedKph = 0.0;
        public double minInclinePercent = 0.0;
        public double maxInclinePercent = 0.0;
        public double minResistance = 0.0;
        public double maxResistance = 0.0;
        public boolean canSetSpeed = false;
        public boolean canSetIncline = false;
        public boolean canSetResistance = false;

        public ConsoleSummary() {
        }

        public ConsoleSummary(ConsoleSummary other) {
            machineName = other.machineName;
            machineType = other.machineType;
            firmwareVersion = other.firmwareVersion;
            serialNumber = other.serialNumber;
            minSpeedKph = other.minSpeedKph;
            maxSpeedKph = other.maxSpeedKph;
            minInclinePercent = other.minInclinePercent;
            maxInclinePercent = other.maxInclinePercent;
            minResistance = other.minResistance;
            maxResistance = other.maxResistance;
            canSetSpeed = other.canSetSpeed;
            canSetIncline = other.canSetIncline;
            canSetResistance = other.canSetResistance;
        }

        public void applyConsoleInfo(ConsoleInfo consoleInfo) {
            if (consoleInfo == null) {
                return;
            }
            machineName = emptyToUnknown(consoleInfo.getName());
            machineType = consoleInfo.getMachineType().name();
            firmwareVersion = emptyToUnknown(consoleInfo.getFirmwareVersion());
            serialNumber = emptyToUnknown(consoleInfo.getProductSerialNumber());
            minSpeedKph = consoleInfo.getMinKph();
            maxSpeedKph = consoleInfo.getMaxKph();
            minInclinePercent = consoleInfo.getMinInclinePercent();
            maxInclinePercent = consoleInfo.getMaxInclinePercent();
            minResistance = consoleInfo.getMinResistance();
            maxResistance = consoleInfo.getMaxResistance();
            canSetSpeed = consoleInfo.getCanSetSpeed();
            canSetIncline = consoleInfo.getCanSetIncline();
            canSetResistance = consoleInfo.getCanSetResistance();
        }
    }

    public static final class LiveMetrics {
        public double speedKph = 0.0;
        public double inclinePercent = 0.0;
        public double distanceKm = 0.0;
        public double resistance = 0.0;
        public double cadenceRpm = 0.0;
        public double watts = 0.0;

        public LiveMetrics() {
        }

        public LiveMetrics(LiveMetrics other) {
            speedKph = other.speedKph;
            inclinePercent = other.inclinePercent;
            distanceKm = other.distanceKm;
            resistance = other.resistance;
            cadenceRpm = other.cadenceRpm;
            watts = other.watts;
        }
    }

    public static final class LastError {
        public String level = "";
        public String eventName = "";
        public String message = "";
        public long timestampMs = 0L;

        public LastError() {
        }

        public LastError(LastError other) {
            level = other.level;
            eventName = other.eventName;
            message = other.message;
            timestampMs = other.timestampMs;
        }

        public boolean hasValue() {
            return message != null && !message.isEmpty();
        }
    }

    public ServiceStartupState serviceState = ServiceStartupState.WAITING_FOR_PERMISSION;
    public BluetoothPermissionState bluetoothPermissionState = BluetoothPermissionState.UNKNOWN;
    public BleState bleState = BleState.STOPPED;
    public BackendState backendState = BackendState.DISCONNECTED;
    public DirconProfile dirconProfile = DirconProfile.DISABLED;
    public String advertisedFtmsName = "NordicFTMS";
    public String dirconServiceName = "Unavailable";
    public ConsoleSummary consoleSummary = new ConsoleSummary();
    public LiveMetrics liveMetrics = new LiveMetrics();
    public LastError lastError = new LastError();
    public int reconnectAttempt = 0;
    public boolean detailedTracingEnabled = false;
    public boolean kickrRunModeEnabled = true;

    public ServiceStatusSnapshot() {
    }

    public ServiceStatusSnapshot(ServiceStatusSnapshot other) {
        serviceState = other.serviceState;
        bluetoothPermissionState = other.bluetoothPermissionState;
        bleState = other.bleState;
        backendState = other.backendState;
        dirconProfile = other.dirconProfile;
        advertisedFtmsName = other.advertisedFtmsName;
        dirconServiceName = other.dirconServiceName;
        consoleSummary = new ConsoleSummary(other.consoleSummary);
        liveMetrics = new LiveMetrics(other.liveMetrics);
        lastError = new LastError(other.lastError);
        reconnectAttempt = other.reconnectAttempt;
        detailedTracingEnabled = other.detailedTracingEnabled;
        kickrRunModeEnabled = other.kickrRunModeEnabled;
    }

    public static String emptyToUnknown(String value) {
        return value == null || value.isEmpty() ? "Unknown" : value;
    }
}
