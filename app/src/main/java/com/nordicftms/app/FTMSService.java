package com.nordicftms.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FTMSService extends Service implements GrpcControlService.BackendUnavailableListener {
    private static final String LOG_TAG = "FTMS";
    private static final String ADVERTISED_DEVICE_NAME = "NordicFTMS";

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
    private static final byte OP_RESPONSE_CODE = (byte) 0x80;

    // Result codes
    private static final byte RESULT_SUCCESS = 0x01;
    private static final byte RESULT_NOT_SUPPORTED = 0x02;
    private static final byte RESULT_CONTROL_NOT_PERMITTED = 0x05;

    private static final String NOTIFICATION_CHANNEL_ID = "ftms_service_channel";
    private static final int NOTIFICATION_ID = 1338;
    private static final long BACKEND_REPORT_THROTTLE_MS = 60_000L;

    private static final double INCLINE_TOLERANCE = 0.3;
    private static final long COMMAND_INTENT_RETENTION_MS = 5000L;
    private static final int MAX_PENDING_INCLINE_TARGETS = 12;

    private final InclineCommandTracker inclineCommandTracker = new InclineCommandTracker(
            INCLINE_TOLERANCE,
            COMMAND_INTENT_RETENTION_MS,
            MAX_PENDING_INCLINE_TARGETS
    );
    private final Set<BluetoothDevice> subscribedDevices = new HashSet<>();
    private final Object startupLock = new Object();

    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private Handler handler;
    private NotificationManager notificationManager;
    private Runnable notificationRunnable;
    private ScheduledExecutorService startupExecutor;
    private boolean controlGranted = false;
    private boolean runtimeStarted = false;
    private boolean startupTaskScheduled = false;
    private boolean destroyed = false;
    private long lastBackendReportAtMs = 0L;
    private long lastPermissionReportAtMs = 0L;
    private int grpcRetryCount = 0;
    private ServiceStartupState startupState = ServiceStartupState.WAITING_FOR_PERMISSION;

    private GrpcControlService grpc;
    private DirconServer dirconServer;

    private BluetoothGattCharacteristic treadmillDataCharacteristic;
    private BluetoothGattCharacteristic indoorBikeDataCharacteristic;
    private BluetoothGattCharacteristic controlPointCharacteristic;
    private BluetoothGattCharacteristic machineStatusCharacteristic;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(LOG_TAG, "FTMSService onCreate");

        handler = new Handler(Looper.getMainLooper());
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        startupExecutor = Executors.newSingleThreadScheduledExecutor();
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        grpc = new GrpcControlService(this, this);

        startupState = BluetoothPermissionGate.hasRequiredPermissions(this)
                ? ServiceStartupState.WAITING_FOR_GRPC
                : ServiceStartupState.WAITING_FOR_PERMISSION;

        startForegroundNotification();

        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            scheduleGrpcStartup(0L);
        } else {
            Log.w(LOG_TAG, "FTMSService created without Bluetooth runtime permissions");
            recordMissingPermissionsIfNeeded("service_on_create", false, false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
            recordMissingPermissionsIfNeeded("service_on_start", isBleExposed(), isDirconExposed());
            return START_NOT_STICKY;
        }

        if (startupState == ServiceStartupState.WAITING_FOR_PERMISSION) {
            transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
        }

        if (!runtimeStarted) {
            scheduleGrpcStartup(0L);
        } else {
            updateForegroundNotification();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "FTMSService onDestroy");
        destroyed = true;

        if (startupExecutor != null) {
            startupExecutor.shutdownNow();
        }

        stopRuntimeComponents();
        if (grpc != null) {
            grpc.disconnect();
        }

        super.onDestroy();
    }

    @Override
    public void onGrpcBackendUnavailable(String source, Throwable error) {
        boolean bleExposed = isBleExposed();
        boolean dirconExposed = isDirconExposed();

        Log.w(LOG_TAG, "GlassOS backend became unavailable via " + source);
        recordGrpcUnavailableIfNeeded(
                startupState == ServiceStartupState.RUNNING ? "running_disconnect" : "waiting_for_grpc",
                source,
                error,
                0,
                bleExposed,
                dirconExposed
        );

        stopRuntimeComponents();
        transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
        scheduleGrpcRetry();
    }

    static long getGrpcRetryDelayMs(int retryCount) {
        if (retryCount <= 0) {
            return 1000L;
        }
        if (retryCount == 1) {
            return 2000L;
        }
        if (retryCount == 2) {
            return 5000L;
        }
        return 10_000L;
    }

    // --- Foreground Notification ---

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "FTMS BLE Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            notificationManager.createNotificationChannel(channel);
        }

        startForeground(NOTIFICATION_ID, buildForegroundNotification());
    }

    private void updateForegroundNotification() {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification());
        }
    }

    private Notification buildForegroundNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("NordicFTMS")
                .setContentText(getNotificationText())
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String getNotificationText() {
        switch (startupState) {
            case WAITING_FOR_PERMISSION:
                return "Open NordicFTMS to grant Bluetooth access.";
            case WAITING_FOR_GRPC:
                return "Waiting for the treadmill backend to become ready.";
            case RUNNING:
            default:
                return "Advertising over Bluetooth and DIRCON.";
        }
    }

    // --- Startup / Recovery ---

    private void scheduleGrpcStartup(long delayMs) {
        synchronized (startupLock) {
            if (destroyed || startupExecutor == null || startupTaskScheduled) {
                return;
            }

            if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
                grpcRetryCount = 0;
                transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
                return;
            }

            startupTaskScheduled = true;
            transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
            startupExecutor.schedule(() -> {
                synchronized (startupLock) {
                    startupTaskScheduled = false;
                }
                attemptGrpcStartup();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleGrpcRetry() {
        synchronized (startupLock) {
            if (destroyed || startupExecutor == null || startupTaskScheduled) {
                return;
            }

            if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
                grpcRetryCount = 0;
                transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
                return;
            }

            long delayMs = getGrpcRetryDelayMs(grpcRetryCount);
            grpcRetryCount++;
            startupTaskScheduled = true;
            transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
            startupExecutor.schedule(() -> {
                synchronized (startupLock) {
                    startupTaskScheduled = false;
                }
                attemptGrpcStartup();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void attemptGrpcStartup() {
        if (destroyed) {
            return;
        }

        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions("grpc_startup_attempt");
            return;
        }

        transitionToState(ServiceStartupState.WAITING_FOR_GRPC);

        int attemptNumber;
        synchronized (startupLock) {
            attemptNumber = grpcRetryCount + 1;
        }

        if (!grpc.connect()) {
            recordGrpcUnavailableIfNeeded(
                    "initial_connect",
                    grpc.getLastConnectionErrorSource(),
                    grpc.getLastConnectionError(),
                    attemptNumber,
                    false,
                    false
            );
            scheduleGrpcRetry();
            return;
        }

        synchronized (startupLock) {
            grpcRetryCount = 0;
        }

        grpc.startSubscriptions();
        handler.post(this::startRuntimeComponents);
    }

    private void startRuntimeComponents() {
        if (destroyed || grpc == null || !grpc.isConnected()) {
            return;
        }

        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions("runtime_start");
            return;
        }

        if (runtimeStarted) {
            transitionToState(ServiceStartupState.RUNNING);
            return;
        }

        dirconServer = new DirconServer(this, grpc, this);
        dirconServer.start();

        setupGattServer();
        startAdvertising();
        startNotificationLoop();

        runtimeStarted = true;
        transitionToState(ServiceStartupState.RUNNING);
        Log.i(LOG_TAG, "FTMS runtime is ready");
    }

    private void stopRuntimeComponents() {
        runtimeStarted = false;
        controlGranted = false;
        subscribedDevices.clear();

        stopNotificationLoop();
        stopAdvertising();
        closeGattServer();

        if (dirconServer != null) {
            dirconServer.stop();
            dirconServer = null;
        }
    }

    private void handleMissingPermissions(String source) {
        boolean bleExposed = isBleExposed();
        boolean dirconExposed = isDirconExposed();

        Log.w(LOG_TAG, "Bluetooth permissions missing during " + source + ": "
                + BluetoothPermissionGate.describeMissingPermissions(this));

        recordMissingPermissionsIfNeeded(source, bleExposed, dirconExposed);
        stopRuntimeComponents();
        if (grpc != null) {
            grpc.disconnect();
        }
        synchronized (startupLock) {
            grpcRetryCount = 0;
        }
        transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
    }

    private void transitionToState(ServiceStartupState newState) {
        if (startupState == newState) {
            updateForegroundNotification();
            return;
        }

        startupState = newState;
        updateForegroundNotification();
    }

    private void recordGrpcUnavailableIfNeeded(
            String startupPhase,
            String source,
            Throwable error,
            int attempt,
            boolean bleExposed,
            boolean dirconExposed
    ) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackendReportAtMs < BACKEND_REPORT_THROTTLE_MS) {
            return;
        }

        lastBackendReportAtMs = now;
        SentryDiagnostics.recordGrpcBackendUnavailable(
                startupPhase,
                source,
                error,
                attempt,
                bleExposed,
                dirconExposed
        );
    }

    private void recordMissingPermissionsIfNeeded(String source, boolean bleExposed, boolean dirconExposed) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPermissionReportAtMs < BACKEND_REPORT_THROTTLE_MS) {
            return;
        }

        lastPermissionReportAtMs = now;
        SentryDiagnostics.recordMissingBluetoothPermissions(
                "waiting_for_permission",
                source,
                BluetoothPermissionGate.describeMissingPermissions(this),
                bleExposed,
                dirconExposed
        );
    }

    private boolean isBleExposed() {
        return gattServer != null || advertiser != null;
    }

    private boolean isDirconExposed() {
        return dirconServer != null;
    }

    // --- GATT Server ---

    private void setupGattServer() {
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            Log.w(LOG_TAG, "Skipping GATT server setup because Bluetooth permissions are missing");
            return;
        }

        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(LOG_TAG, "Bluetooth not available or not enabled");
            return;
        }

        closeGattServer();
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback);
        if (gattServer == null) {
            Log.e(LOG_TAG, "Failed to open GATT server");
            return;
        }

        BluetoothGattService ftmsService = new BluetoothGattService(
                FTMS_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        BluetoothGattCharacteristic featureChar = new BluetoothGattCharacteristic(
                FITNESS_MACHINE_FEATURE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        ftmsService.addCharacteristic(featureChar);

        treadmillDataCharacteristic = new BluetoothGattCharacteristic(
                TREADMILL_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0
        );
        treadmillDataCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(treadmillDataCharacteristic);

        indoorBikeDataCharacteristic = new BluetoothGattCharacteristic(
                INDOOR_BIKE_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0
        );
        indoorBikeDataCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(indoorBikeDataCharacteristic);

        controlPointCharacteristic = new BluetoothGattCharacteristic(
                CONTROL_POINT_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        controlPointCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(controlPointCharacteristic);

        machineStatusCharacteristic = new BluetoothGattCharacteristic(
                MACHINE_STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0
        );
        machineStatusCharacteristic.addDescriptor(createCCCD());
        ftmsService.addCharacteristic(machineStatusCharacteristic);

        BluetoothGattCharacteristic speedRangeChar = new BluetoothGattCharacteristic(
                SUPPORTED_SPEED_RANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        ftmsService.addCharacteristic(speedRangeChar);

        BluetoothGattCharacteristic inclinationRangeChar = new BluetoothGattCharacteristic(
                SUPPORTED_INCLINATION_RANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        ftmsService.addCharacteristic(inclinationRangeChar);

        BluetoothGattCharacteristic resistanceRangeChar = new BluetoothGattCharacteristic(
                SUPPORTED_RESISTANCE_RANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        ftmsService.addCharacteristic(resistanceRangeChar);

        gattServer.addService(ftmsService);
        Log.i(LOG_TAG, "GATT server setup complete with FTMS service");
    }

    private BluetoothGattDescriptor createCCCD() {
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        );
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        return cccd;
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(LOG_TAG, "Device connected: " + safeDeviceLabel(device));
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(LOG_TAG, "Device disconnected: " + safeDeviceLabel(device));
                subscribedDevices.remove(device);
                controlGranted = false;
            }
        }

        @Override
        public void onCharacteristicReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattCharacteristic characteristic
        ) {
            if (gattServer == null) {
                return;
            }

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
                writeUint16LE(value, 4, 10);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_INCLINATION_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minIncline = (int) (getMinInclinePercent() * 10);
                int maxIncline = (int) (getMaxInclinePercent() * 10);
                writeInt16LE(value, 0, minIncline);
                writeInt16LE(value, 2, maxIncline);
                writeUint16LE(value, 4, 5);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_RESISTANCE_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minRes = (int) (getMinResistance() * 10);
                int maxRes = (int) (getMaxResistance() * 10);
                writeUint16LE(value, 0, minRes);
                writeUint16LE(value, 2, maxRes);
                writeUint16LE(value, 4, 10);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattCharacteristic characteristic,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            if (gattServer == null) {
                return;
            }

            if (characteristic.getUuid().equals(CONTROL_POINT_UUID)) {
                byte[] response = handleControlPoint(value);
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
                controlPointCharacteristic.setValue(response);
                gattServer.notifyCharacteristicChanged(device, controlPointCharacteristic, true);
            } else if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattDescriptor descriptor
        ) {
            if (gattServer == null) {
                return;
            }

            if (descriptor.getUuid().equals(CCCD_UUID)) {
                byte[] value = subscribedDevices.contains(device)
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattDescriptor descriptor,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            if (gattServer == null) {
                return;
            }

            if (descriptor.getUuid().equals(CCCD_UUID)) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        || Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    subscribedDevices.add(device);
                    Log.i(LOG_TAG, "Device subscribed: " + safeDeviceLabel(device));
                } else {
                    subscribedDevices.remove(device);
                    Log.i(LOG_TAG, "Device unsubscribed: " + safeDeviceLabel(device));
                }
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            } else if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }
    };

    public void setFtmsTargetIncline(double incline) {
        inclineCommandTracker.setTargetIncline(incline, System.currentTimeMillis());
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
                    setFtmsTargetIncline(inclination);
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
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            Log.w(LOG_TAG, "Skipping BLE advertising because Bluetooth permissions are missing");
            return;
        }

        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null) {
            Log.e(LOG_TAG, "No Bluetooth adapter");
            return;
        }

        adapter.setName(ADVERTISED_DEVICE_NAME);

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
        Log.i(LOG_TAG, "Started BLE advertising as \"" + ADVERTISED_DEVICE_NAME + "\"");
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error stopping advertising", e);
            }
            advertiser = null;
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
        if (notificationRunnable != null) {
            return;
        }

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
            notificationRunnable = null;
        }
    }

    private void sendDataNotifications() {
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions("notification_loop");
            return;
        }

        if (gattServer == null || subscribedDevices.isEmpty() || grpc == null || !grpc.isConnected()) {
            return;
        }

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
                Log.e(LOG_TAG, "Error sending notification to " + safeDeviceLabel(device), e);
            }
        }
    }

    private void checkForManualInclineChange() {
        double currentIncline = grpc.getLastInclinePercent();
        InclineCommandTracker.ChangeResult changeResult = inclineCommandTracker.updateObservedIncline(
                currentIncline,
                System.currentTimeMillis()
        );
        if (changeResult != InclineCommandTracker.ChangeResult.MANUAL_OVERRIDE) {
            return;
        }

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
                Log.e(LOG_TAG, "Error sending Machine Status to " + safeDeviceLabel(device), e);
            }
        }

        if (dirconServer != null) {
            dirconServer.sendMachineStatusToAll(status);
        }
    }

    // --- Data Builders ---

    private byte[] buildTreadmillData() {
        byte[] data = new byte[11];

        writeUint16LE(data, 0, 0x000C);

        int speed = (int) (grpc.getLastSpeedKph() * 100);
        if (speed < 0) {
            speed = 0;
        }
        writeUint16LE(data, 2, speed);

        int distMeters = (int) (grpc.getLastDistanceKm() * 1000);
        if (distMeters < 0) {
            distMeters = 0;
        }
        data[4] = (byte) (distMeters & 0xFF);
        data[5] = (byte) ((distMeters >> 8) & 0xFF);
        data[6] = (byte) ((distMeters >> 16) & 0xFF);

        int inclination = (int) (grpc.getLastInclinePercent() * 10);
        writeInt16LE(data, 7, inclination);
        writeInt16LE(data, 9, 0);

        return data;
    }

    private byte[] buildIndoorBikeData() {
        byte[] data = new byte[10];

        writeUint16LE(data, 0, 0x0064);

        int speed = (int) (grpc.getLastSpeedKph() * 100);
        if (speed < 0) {
            speed = 0;
        }
        writeUint16LE(data, 2, speed);

        int cadence = (int) (grpc.getLastCadenceRpm() * 2);
        writeUint16LE(data, 4, cadence);

        int resistance = (int) (grpc.getLastResistance() * 10);
        writeInt16LE(data, 6, resistance);

        int power = (int) grpc.getLastWatts();
        writeInt16LE(data, 8, power);

        return data;
    }

    // --- Cleanup ---

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

    private String safeDeviceLabel(BluetoothDevice device) {
        if (device == null) {
            return "unknown-device";
        }
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            return "permission-blocked-device";
        }
        return device.getAddress();
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
        if (val >= 0x8000) {
            val -= 0x10000;
        }
        return val;
    }
}
