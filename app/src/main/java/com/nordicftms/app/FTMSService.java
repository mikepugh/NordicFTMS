package com.nordicftms.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.ifit.glassos.ConsoleInfo;
import com.ifit.glassos.ConsoleType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class FTMSService extends Service {
    private static final String LOG_TAG = "FTMS";

    // FTMS UUIDs
    private static final UUID FTMS_SERVICE_UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb");
    private static final UUID FITNESS_MACHINE_FEATURE_UUID = UUID.fromString("00002ACC-0000-1000-8000-00805f9b34fb");
    private static final UUID TREADMILL_DATA_UUID = UUID.fromString("00002ACD-0000-1000-8000-00805f9b34fb");
    private static final UUID INDOOR_BIKE_DATA_UUID = UUID.fromString("00002AD2-0000-1000-8000-00805f9b34fb");
    private static final UUID CONTROL_POINT_UUID = UUID.fromString("00002AD9-0000-1000-8000-00805f9b34fb");
    private static final UUID MACHINE_STATUS_UUID = UUID.fromString("00002ADA-0000-1000-8000-00805f9b34fb");
    private static final UUID SUPPORTED_SPEED_RANGE_UUID = UUID.fromString("00002AD4-0000-1000-8000-00805f9b34fb");
    private static final UUID SUPPORTED_INCLINATION_RANGE_UUID = UUID.fromString("00002AD5-0000-1000-8000-00805f9b34fb");
    private static final UUID SUPPORTED_RESISTANCE_RANGE_UUID = UUID.fromString("00002AD6-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Control Point opcodes (per FTMS v1.0 spec)
    private static final byte OP_REQUEST_CONTROL = 0x00;
    private static final byte OP_RESET = 0x01;
    private static final byte OP_SET_TARGET_SPEED = 0x02;
    private static final byte OP_SET_TARGET_INCLINATION = 0x03;
    private static final byte OP_SET_TARGET_RESISTANCE = 0x04;
    private static final byte OP_SET_TARGET_POWER = 0x05;
    private static final byte OP_SET_TARGET_HEART_RATE = 0x06;
    private static final byte OP_START_OR_RESUME = 0x07;
    private static final byte OP_STOP_OR_PAUSE = 0x08;
    private static final byte OP_SET_INDOOR_BIKE_SIMULATION = 0x11;
    private static final byte OP_RESPONSE_CODE = (byte) 0x80;

    // Result codes
    private static final byte RESULT_SUCCESS = 0x01;
    private static final byte RESULT_NOT_SUPPORTED = 0x02;
    private static final byte RESULT_CONTROL_NOT_PERMITTED = 0x05;

    private static final String NOTIFICATION_CHANNEL_ID = "ftms_service_channel";
    private static final int NOTIFICATION_ID = 1338;

    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private Handler handler;
    private Runnable notificationRunnable;
    private boolean controlGranted = false;

    // Incline change tracking for Machine Status notifications
    private double lastNotifiedIncline = Double.NaN;
    private double ftmsTargetIncline = Double.NaN;
    private static final double INCLINE_TOLERANCE = 0.3; // percent

    // gRPC client for hardware communication
    private GrpcControlService grpc;

    // DIRCON server for WiFi/TCP connectivity
    private DirconServer dirconServer;

    private final Set<BluetoothDevice> subscribedDevices = new HashSet<>();

    private BluetoothGattCharacteristic treadmillDataCharacteristic;
    private BluetoothGattCharacteristic indoorBikeDataCharacteristic;
    private BluetoothGattCharacteristic controlPointCharacteristic;
    private BluetoothGattCharacteristic machineStatusCharacteristic;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(LOG_TAG, "FTMSService onCreate");

        handler = new Handler();
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        startForegroundNotification();

        // Connect to GlassOS gRPC server on a background thread, then set up BLE
        new Thread(() -> {
            grpc = new GrpcControlService(FTMSService.this);
            grpc.connect();

            if (grpc.isConnected()) {
                grpc.startSubscriptions();
                Log.i(LOG_TAG, "gRPC connected, machine type: " + grpc.getMachineType());
            } else {
                Log.w(LOG_TAG, "gRPC connection failed — BLE will advertise but data/control may not work");
            }

            // Start DIRCON server (WiFi/TCP)
            dirconServer = new DirconServer(FTMSService.this, grpc, FTMSService.this);
            dirconServer.start();

            // Set up BLE on the main thread after gRPC connects
            handler.post(() -> {
                setupGattServer();
                startAdvertising();
                startNotificationLoop();
            });
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "FTMSService onDestroy");
        stopNotificationLoop();
        stopAdvertising();
        closeGattServer();
        if (dirconServer != null) {
            dirconServer.stop();
        }
        if (grpc != null) {
            grpc.disconnect();
        }
        super.onDestroy();
    }

    // --- Foreground Notification ---

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "FTMS BLE Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("FTMS BLE Server")
                .setContentText("Advertising...")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    // --- GATT Server ---

    private void setupGattServer() {
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(LOG_TAG, "Bluetooth not available or not enabled");
            return;
        }

        gattServer = bluetoothManager.openGattServer(this, gattServerCallback);
        if (gattServer == null) {
            Log.e(LOG_TAG, "Failed to open GATT server");
            return;
        }

        BluetoothGattService ftmsService = new BluetoothGattService(
                FTMS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Fitness Machine Feature (Read)
        BluetoothGattCharacteristic featureChar = new BluetoothGattCharacteristic(
                FITNESS_MACHINE_FEATURE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        ftmsService.addCharacteristic(featureChar);

        // Treadmill Data (Notify)
        treadmillDataCharacteristic = new BluetoothGattCharacteristic(
                TREADMILL_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        treadmillDataCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(treadmillDataCharacteristic);

        // Indoor Bike Data (Notify)
        indoorBikeDataCharacteristic = new BluetoothGattCharacteristic(
                INDOOR_BIKE_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        indoorBikeDataCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(indoorBikeDataCharacteristic);

        // Control Point (Write + Indicate)
        controlPointCharacteristic = new BluetoothGattCharacteristic(
                CONTROL_POINT_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        controlPointCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(controlPointCharacteristic);

        // Machine Status (Notify)
        machineStatusCharacteristic = new BluetoothGattCharacteristic(
                MACHINE_STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        machineStatusCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(machineStatusCharacteristic);

        // Supported Speed Range (Read)
        BluetoothGattCharacteristic speedRangeChar = new BluetoothGattCharacteristic(
                SUPPORTED_SPEED_RANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        ftmsService.addCharacteristic(speedRangeChar);

        // Supported Inclination Range (Read)
        BluetoothGattCharacteristic inclinationRangeChar = new BluetoothGattCharacteristic(
                SUPPORTED_INCLINATION_RANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        ftmsService.addCharacteristic(inclinationRangeChar);

        // Supported Resistance Range (Read)
        BluetoothGattCharacteristic resistanceRangeChar = new BluetoothGattCharacteristic(
                SUPPORTED_RESISTANCE_RANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        ftmsService.addCharacteristic(resistanceRangeChar);

        gattServer.addService(ftmsService);
        Log.i(LOG_TAG, "GATT server setup complete with FTMS service");
    }

    private BluetoothGattDescriptor createCCCD() {
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        return cccd;
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(LOG_TAG, "Device connected: " + device.getAddress());
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(LOG_TAG, "Device disconnected: " + device.getAddress());
                subscribedDevices.remove(device);
                controlGranted = false;
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();

            if (uuid.equals(FITNESS_MACHINE_FEATURE_UUID)) {
                byte[] value = buildFeatureValue();
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_SPEED_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minSpeed = (int) (getMinSpeedKph() * 100);
                int maxSpeed = (int) (getMaxSpeedKph() * 100);
                writeUint16LE(value, 0, minSpeed);
                writeUint16LE(value, 2, maxSpeed);
                writeUint16LE(value, 4, 10);    // step: 0.10 km/h
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_INCLINATION_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minIncline = (int) (getMinInclinePercent() * 10);
                int maxIncline = (int) (getMaxInclinePercent() * 10);
                writeInt16LE(value, 0, minIncline);
                writeInt16LE(value, 2, maxIncline);
                writeUint16LE(value, 4, 5);     // step: 0.5%
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_RESISTANCE_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minRes = (int) (getMinResistance() * 10);
                int maxRes = (int) (getMaxResistance() * 10);
                writeUint16LE(value, 0, minRes);
                writeUint16LE(value, 2, maxRes);
                writeUint16LE(value, 4, 10);    // step: 1.0
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite, boolean responseNeeded,
                                                  int offset, byte[] value) {
            if (characteristic.getUuid().equals(CONTROL_POINT_UUID)) {
                byte[] response = handleControlPoint(value);
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
                // Send indication with the response
                controlPointCharacteristic.setValue(response);
                gattServer.notifyCharacteristicChanged(device, controlPointCharacteristic, true);
            } else {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                             BluetoothGattDescriptor descriptor) {
            if (descriptor.getUuid().equals(CCCD_UUID)) {
                byte[] value = subscribedDevices.contains(device) ?
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE :
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                              BluetoothGattDescriptor descriptor,
                                              boolean preparedWrite, boolean responseNeeded,
                                              int offset, byte[] value) {
            if (descriptor.getUuid().equals(CCCD_UUID)) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    subscribedDevices.add(device);
                    Log.i(LOG_TAG, "Device subscribed: " + device.getAddress());
                } else {
                    subscribedDevices.remove(device);
                    Log.i(LOG_TAG, "Device unsubscribed: " + device.getAddress());
                }
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            } else {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }
    };

    /**
     * Called by DirconServer when an incline command arrives via DIRCON,
     * so the BLE side knows not to treat the resulting change as manual.
     */
    public void setFtmsTargetIncline(double incline) {
        this.ftmsTargetIncline = incline;
    }

    // --- Control Point ---

    private byte[] handleControlPoint(byte[] value) {
        if (value == null || value.length < 1) {
            return new byte[]{OP_RESPONSE_CODE, 0x00, RESULT_NOT_SUPPORTED};
        }

        byte opcode = value[0];
        Log.i(LOG_TAG, "Control Point opcode: 0x" + String.format("%02X", opcode));

        switch (opcode) {
            case OP_REQUEST_CONTROL:
                controlGranted = true;
                Log.i(LOG_TAG, "Control granted");
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};

            case OP_RESET:
                controlGranted = false;
                Log.i(LOG_TAG, "Reset received");
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};

            case OP_SET_TARGET_SPEED:
                if (!controlGranted) {
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_CONTROL_NOT_PERMITTED};
                }
                if (value.length >= 3) {
                    int speedRaw = readUint16LE(value, 1);
                    double speedKmh = speedRaw / 100.0;
                    Log.i(LOG_TAG, "Set target speed: " + speedKmh + " km/h (via gRPC)");
                    if (grpc != null) {
                        grpc.setSpeed(speedKmh);
                    }
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};
                }
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_NOT_SUPPORTED};

            case OP_SET_TARGET_INCLINATION:
                if (!controlGranted) {
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_CONTROL_NOT_PERMITTED};
                }
                if (value.length >= 3) {
                    int inclRaw = readInt16LE(value, 1);
                    double inclination = inclRaw / 10.0;
                    Log.i(LOG_TAG, "Set target inclination: " + inclination + "% (via gRPC)");
                    ftmsTargetIncline = inclination;
                    // Notify DIRCON so it doesn't treat this as a manual change
                    if (dirconServer != null) {
                        dirconServer.setFtmsTargetIncline(inclination);
                    }
                    if (grpc != null) {
                        grpc.setIncline(inclination);
                    }
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};
                }
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_NOT_SUPPORTED};

            case OP_SET_TARGET_RESISTANCE:
                if (!controlGranted) {
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_CONTROL_NOT_PERMITTED};
                }
                if (value.length >= 2) {
                    int resRaw = value[1] & 0xFF;
                    double resistance = resRaw / 10.0;
                    Log.i(LOG_TAG, "Set target resistance: " + resistance + " (via gRPC)");
                    if (grpc != null) {
                        grpc.setResistance(resistance);
                    }
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};
                }
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_NOT_SUPPORTED};

            default:
                Log.w(LOG_TAG, "Unsupported opcode: 0x" + String.format("%02X", opcode));
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_NOT_SUPPORTED};
        }
    }

    // --- Feature Value ---

    private byte[] buildFeatureValue() {
        byte[] value = new byte[8];

        boolean isBike = grpc != null && grpc.isBikeDevice();

        if (isBike) {
            int features = (1 << 1) | (1 << 7) | (1 << 14);
            writeUint32LE(value, 0, features);
            int targets = (1 << 2);
            writeUint32LE(value, 4, targets);
        } else {
            // Treadmill/Incline Trainer
            int features = (1 << 0) | (1 << 1) | (1 << 13);
            writeUint32LE(value, 0, features);
            int targets = (1 << 0) | (1 << 1);
            writeUint32LE(value, 4, targets);
        }

        return value;
    }

    // --- Range helpers (from gRPC ConsoleInfo) ---

    private double getMinSpeedKph() {
        return grpc != null ? grpc.getMinSpeedKph() : 0.5;
    }

    private double getMaxSpeedKph() {
        return grpc != null ? grpc.getMaxSpeedKph() : 22.0;
    }

    private double getMinInclinePercent() {
        return grpc != null ? grpc.getMinInclinePercent() : -6.0;
    }

    private double getMaxInclinePercent() {
        return grpc != null ? grpc.getMaxInclinePercent() : 40.0;
    }

    private double getMinResistance() {
        return grpc != null ? grpc.getMinResistance() : 0;
    }

    private double getMaxResistance() {
        return grpc != null ? grpc.getMaxResistance() : 30;
    }

    // --- BLE Advertising ---

    private void startAdvertising() {
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null) {
            Log.e(LOG_TAG, "No Bluetooth adapter");
            return;
        }

        // Set BLE name based on detected machine type
        String bleName = "FTMS Tread";
        if (grpc != null) {
            if (grpc.isBikeDevice()) {
                bleName = "FTMS Bike";
            } else if (grpc.isRower()) {
                bleName = "FTMS Rower";
            } else if (grpc.isElliptical()) {
                bleName = "FTMS Ellip";
            }
        }
        adapter.setName(bleName);

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(LOG_TAG, "BLE advertising not supported");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(FTMS_SERVICE_UUID))
                .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .build();

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        Log.i(LOG_TAG, "Started BLE advertising as \"" + bleName + "\"");
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error stopping advertising", e);
            }
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(LOG_TAG, "BLE advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(LOG_TAG, "BLE advertising failed with error code: " + errorCode);
        }
    };

    // --- Notification Loop ---

    private void startNotificationLoop() {
        notificationRunnable = new Runnable() {
            @Override
            public void run() {
                sendDataNotifications();
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(notificationRunnable, 1000);
    }

    private void stopNotificationLoop() {
        if (handler != null && notificationRunnable != null) {
            handler.removeCallbacks(notificationRunnable);
        }
    }

    private void sendDataNotifications() {
        if (gattServer == null || subscribedDevices.isEmpty() || grpc == null) {
            return;
        }

        // Check for manual incline change (hardware controls, not FTMS command)
        checkForManualInclineChange();

        boolean isBike = grpc.isBikeDevice();

        for (BluetoothDevice device : new HashSet<>(subscribedDevices)) {
            try {
                if (isBike) {
                    byte[] bikeData = buildIndoorBikeData();
                    indoorBikeDataCharacteristic.setValue(bikeData);
                    gattServer.notifyCharacteristicChanged(device, indoorBikeDataCharacteristic, false);
                } else {
                    byte[] treadmillData = buildTreadmillData();
                    treadmillDataCharacteristic.setValue(treadmillData);
                    gattServer.notifyCharacteristicChanged(device, treadmillDataCharacteristic, false);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error sending notification to " + device.getAddress(), e);
            }
        }
    }

    /**
     * Detects incline changes from hardware controls and sends a Machine Status
     * notification (Target Inclination Changed, opcode 0x08). If the incline is
     * moving toward the FTMS-commanded target, it's treated as an FTMS-initiated
     * change. If it moves to a value that doesn't match the FTMS target, it's
     * a manual override from hardware controls.
     */
    private void checkForManualInclineChange() {
        double currentIncline = grpc.getLastInclinePercent();

        if (Double.isNaN(lastNotifiedIncline)) {
            lastNotifiedIncline = currentIncline;
            return;
        }

        if (currentIncline == lastNotifiedIncline) {
            return;
        }

        double previousIncline = lastNotifiedIncline;
        lastNotifiedIncline = currentIncline;

        // If FTMS has a target and current incline is moving toward it, skip notification
        if (!Double.isNaN(ftmsTargetIncline)) {
            double prevDistance = Math.abs(previousIncline - ftmsTargetIncline);
            double currDistance = Math.abs(currentIncline - ftmsTargetIncline);

            if (currDistance < prevDistance || currDistance <= INCLINE_TOLERANCE) {
                // Moving toward or arrived at the FTMS target — not a manual change
                if (currDistance <= INCLINE_TOLERANCE) {
                    // Arrived at target, clear it
                    ftmsTargetIncline = Double.NaN;
                }
                return;
            }

            // Moving away from FTMS target — this is a manual override
            ftmsTargetIncline = Double.NaN;
        }

        // Manual change from hardware controls — notify connected devices
        // FTMS Machine Status opcode 0x06 = Target Inclination Changed
        // Parameter: int16 LE, resolution 0.1%
        int inclRaw = (int) (currentIncline * 10);
        byte[] status = new byte[3];
        status[0] = 0x06;
        writeInt16LE(status, 1, inclRaw);

        Log.i(LOG_TAG, "Manual incline change detected: " + currentIncline + "% — sending Machine Status");

        for (BluetoothDevice device : new HashSet<>(subscribedDevices)) {
            try {
                machineStatusCharacteristic.setValue(status);
                gattServer.notifyCharacteristicChanged(device, machineStatusCharacteristic, false);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error sending Machine Status to " + device.getAddress(), e);
            }
        }

        // Also notify DIRCON clients
        if (dirconServer != null) {
            dirconServer.sendMachineStatusToAll(status);
        }
    }

    // --- Data Builders (now powered by gRPC subscriptions) ---

    private byte[] buildTreadmillData() {
        // Flags (uint16 LE) per FTMS spec Table 4.7:
        //   bit 0 = 0: instantaneous speed IS present
        //   bit 2 = 1: total distance present
        //   bit 3 = 1: inclination + ramp angle present
        // Flags = 0x000C (bits 2 and 3 set)
        byte[] data = new byte[11];

        writeUint16LE(data, 0, 0x000C);

        // Instantaneous Speed (uint16 LE, resolution 0.01 km/h)
        int speed = (int) (grpc.getLastSpeedKph() * 100);
        if (speed < 0) speed = 0;
        writeUint16LE(data, 2, speed);

        // Total Distance (uint24 LE, resolution 1 meter)
        int distMeters = (int) (grpc.getLastDistanceKm() * 1000);
        if (distMeters < 0) distMeters = 0;
        data[4] = (byte) (distMeters & 0xFF);
        data[5] = (byte) ((distMeters >> 8) & 0xFF);
        data[6] = (byte) ((distMeters >> 16) & 0xFF);

        // Inclination (int16 LE, resolution 0.1%)
        int inclination = (int) (grpc.getLastInclinePercent() * 10);
        writeInt16LE(data, 7, inclination);

        // Ramp Angle Setting (int16 LE, resolution 0.1 degrees) — set to 0
        writeInt16LE(data, 9, 0);

        return data;
    }

    private byte[] buildIndoorBikeData() {
        // Flags: bits 2, 5, 6 set = 0x0064
        byte[] data = new byte[10];

        writeUint16LE(data, 0, 0x0064);

        // Instantaneous Speed (uint16 LE, resolution 0.01 km/h)
        int speed = (int) (grpc.getLastSpeedKph() * 100);
        if (speed < 0) speed = 0;
        writeUint16LE(data, 2, speed);

        // Instantaneous Cadence (uint16 LE, resolution 0.5 rpm)
        int cadence = (int) (grpc.getLastCadenceRpm() * 2);
        writeUint16LE(data, 4, cadence);

        // Resistance Level (int16 LE, resolution 0.1)
        int resistance = (int) (grpc.getLastResistance() * 10);
        writeInt16LE(data, 6, resistance);

        // Instantaneous Power (int16 LE, watts)
        int power = (int) grpc.getLastWatts();
        writeInt16LE(data, 8, power);

        return data;
    }

    // --- GATT Server Cleanup ---

    private void closeGattServer() {
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error closing GATT server", e);
            }
            gattServer = null;
        }
    }

    // --- Byte Helpers ---

    private static void writeUint16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeInt16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeUint32LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static int readUint16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt16LE(byte[] data, int offset) {
        int val = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        if (val >= 0x8000) val -= 0x10000;
        return val;
    }
}
