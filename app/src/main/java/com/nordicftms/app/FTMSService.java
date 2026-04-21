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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FTMSService extends Service implements GrpcControlService.BackendUnavailableListener {
    private static final String LOG_TAG = "FTMS";
    private static final String ADVERTISED_DEVICE_NAME = "NordicFTMS";
    public static final String ACTION_RETRY_BACKEND = "com.nordicftms.app.action.RETRY_BACKEND";
    public static final String ACTION_RESTART_BLUETOOTH = "com.nordicftms.app.action.RESTART_BLUETOOTH";
    public static final String ACTION_PREFERENCES_CHANGED = "com.nordicftms.app.action.PREFERENCES_CHANGED";

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

    private final InclineCommandTracker inclineCommandTracker = new InclineCommandTracker(
            InclineTrackerConstants.INCLINE_TOLERANCE,
            InclineTrackerConstants.COMMAND_INTENT_RETENTION_MS,
            InclineTrackerConstants.MAX_PENDING_INCLINE_TARGETS,
            InclineTrackerConstants.COMMAND_COOLDOWN_MS
    );
    private final Set<BluetoothDevice> subscribedDevices = new CopyOnWriteArraySet<>();
    private final Object startupLock = new Object();

    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private Handler handler;
    private NotificationManager notificationManager;
    private Runnable notificationRunnable;
    private ScheduledExecutorService startupExecutor;
    private ScheduledFuture<?> startupFuture;
    private boolean controlGranted = false;
    private boolean runtimeStarted = false;
    private boolean startupTaskScheduled = false;
    private boolean destroyed = false;
    private long lastBackendReportAtMs = 0L;
    private long lastPermissionReportAtMs = 0L;
    private int grpcRetryCount = 0;
    private ServiceStartupState startupState = ServiceStartupState.WAITING_FOR_PERMISSION;
    private ServiceStatusSnapshot.BleState bleState = ServiceStatusSnapshot.BleState.STOPPED;
    private ServiceStatusSnapshot.BackendState backendState = ServiceStatusSnapshot.BackendState.DISCONNECTED;
    private ServiceStatusSnapshot.DirconProfile dirconProfile = ServiceStatusSnapshot.DirconProfile.DISABLED;
    private String dirconServiceName = "Unavailable";

    private GrpcControlService grpc;
    private DirconServer dirconServer;
    private BroadcastReceiver bluetoothStateReceiver;
    private final NordicFtmsLogger logger = NordicFtmsLogger.getInstance();

    private BluetoothGattCharacteristic treadmillDataCharacteristic;
    private BluetoothGattCharacteristic indoorBikeDataCharacteristic;
    private BluetoothGattCharacteristic controlPointCharacteristic;
    private BluetoothGattCharacteristic machineStatusCharacteristic;

    @Override
    public void onCreate() {
        super.onCreate();
        logger.info(this, "service_create", "FTMSService created");

        handler = new Handler(Looper.getMainLooper());
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        startupExecutor = Executors.newSingleThreadScheduledExecutor();
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        grpc = new GrpcControlService(this, this);
        NordicFtmsPreferences.syncStatusSnapshot(this);

        startupState = BluetoothPermissionGate.hasRequiredPermissions(this)
                ? ServiceStartupState.WAITING_FOR_GRPC
                : ServiceStartupState.WAITING_FOR_PERMISSION;
        bleState = BluetoothPermissionGate.hasRequiredPermissions(this)
                ? ServiceStatusSnapshot.BleState.STOPPED
                : ServiceStatusSnapshot.BleState.WAITING_FOR_PERMISSION;
        backendState = ServiceStatusSnapshot.BackendState.DISCONNECTED;

        registerBluetoothStateReceiver();
        startForegroundNotification();
        refreshStatusSnapshot();

        if (BluetoothPermissionGate.hasRequiredPermissions(this)) {
            scheduleGrpcStartup(0L);
        } else {
            logger.warn(this, "service_permissions_missing", "FTMSService created without Bluetooth runtime permissions");
            recordMissingPermissionsIfNeeded("service_on_create", false, false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_RETRY_BACKEND.equals(action)) {
            forceBackendRetry("manual_retry");
            return START_STICKY;
        }
        if (ACTION_RESTART_BLUETOOTH.equals(action)) {
            restartBluetoothPeripheral("manual_bluetooth_restart");
            return START_STICKY;
        }
        if (ACTION_PREFERENCES_CHANGED.equals(action)) {
            handlePreferencesChanged();
            return START_STICKY;
        }

        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
            bleState = ServiceStatusSnapshot.BleState.WAITING_FOR_PERMISSION;
            recordMissingPermissionsIfNeeded("service_on_start", isBleExposed(), isDirconExposed());
            refreshStatusSnapshot();
            return START_STICKY;
        }

        if (startupState == ServiceStartupState.WAITING_FOR_PERMISSION) {
            transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
        }

        if (!runtimeStarted) {
            scheduleGrpcStartup(0L);
        } else {
            updateForegroundNotification();
        }

        refreshStatusSnapshot();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        logger.info(this, "service_destroy", "FTMSService destroyed");
        destroyed = true;

        unregisterBluetoothStateReceiver();

        synchronized (startupLock) {
            if (startupExecutor != null) {
                startupExecutor.shutdownNow();
            }
        }

        stopRuntimeComponents();
        if (grpc != null) {
            grpc.shutdown();
        }

        super.onDestroy();
    }

    @Override
    public void onGrpcBackendUnavailable(String source, Throwable error) {
        boolean bleExposed = isBleExposed();
        boolean dirconExposed = isDirconExposed();

        logger.info(this, "backend_unavailable", "GlassOS backend became unavailable via " + source + "; retrying");
        recordGrpcUnavailableIfNeeded(
                startupState == ServiceStartupState.RUNNING ? "running_disconnect" : "waiting_for_grpc",
                source,
                error,
                0,
                bleExposed,
                dirconExposed
        );

        stopRuntimeComponents();
        backendState = ServiceStatusSnapshot.BackendState.RETRYING;
        transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
        scheduleGrpcRetry();
        refreshStatusSnapshot();
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
                return backendState == ServiceStatusSnapshot.BackendState.RETRYING
                        ? "Trying to reconnect to the treadmill backend."
                        : "Waiting for the treadmill backend to become ready.";
            case RUNNING:
            default:
                if (bleState == ServiceStatusSnapshot.BleState.ERROR) {
                    return "Bluetooth peripheral failed. Open NordicFTMS for details.";
                }
                return "NordicFTMS is running. Open the app for live status.";
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
                bleState = ServiceStatusSnapshot.BleState.WAITING_FOR_PERMISSION;
                transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
                refreshStatusSnapshot();
                return;
            }

            startupTaskScheduled = true;
            backendState = delayMs > 0L
                    ? ServiceStatusSnapshot.BackendState.RETRYING
                    : ServiceStatusSnapshot.BackendState.CONNECTING;
            transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
            startupFuture = startupExecutor.schedule(() -> {
                synchronized (startupLock) {
                    startupTaskScheduled = false;
                    startupFuture = null;
                }
                attemptGrpcStartup();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
        refreshStatusSnapshot();
    }

    private void scheduleGrpcRetry() {
        synchronized (startupLock) {
            if (destroyed || startupExecutor == null || startupTaskScheduled) {
                return;
            }

            if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
                grpcRetryCount = 0;
                bleState = ServiceStatusSnapshot.BleState.WAITING_FOR_PERMISSION;
                transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
                refreshStatusSnapshot();
                return;
            }

            long delayMs = getGrpcRetryDelayMs(grpcRetryCount);
            grpcRetryCount++;
            startupTaskScheduled = true;
            backendState = ServiceStatusSnapshot.BackendState.RETRYING;
            transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
            startupFuture = startupExecutor.schedule(() -> {
                synchronized (startupLock) {
                    startupTaskScheduled = false;
                    startupFuture = null;
                }
                attemptGrpcStartup();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
        refreshStatusSnapshot();
    }

    private void attemptGrpcStartup() {
        if (destroyed) {
            return;
        }

        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions("grpc_startup_attempt");
            return;
        }

        backendState = ServiceStatusSnapshot.BackendState.CONNECTING;
        transitionToState(ServiceStartupState.WAITING_FOR_GRPC);
        refreshStatusSnapshot();

        int attemptNumber;
        synchronized (startupLock) {
            attemptNumber = grpcRetryCount + 1;
        }

        if (!grpc.connect()) {
            backendState = ServiceStatusSnapshot.BackendState.RETRYING;
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

        backendState = ServiceStatusSnapshot.BackendState.READY;
        grpc.startSubscriptions();
        handler.post(this::startRuntimeComponents);
        refreshStatusSnapshot();
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
            backendState = ServiceStatusSnapshot.BackendState.READY;
            bleState = ServiceStatusSnapshot.BleState.ACTIVE;
            transitionToState(ServiceStartupState.RUNNING);
            clearRecoveredStatusError();
            refreshStatusSnapshot();
            return;
        }

        bleState = ServiceStatusSnapshot.BleState.STARTING;
        refreshStatusSnapshot();

        if (!setupGattServer()) {
            bleState = ServiceStatusSnapshot.BleState.ERROR;
            refreshStatusSnapshot();
            return;
        }

        if (!startAdvertising()) {
            closeGattServer();
            bleState = ServiceStatusSnapshot.BleState.ERROR;
            refreshStatusSnapshot();
            return;
        }

        dirconServer = new DirconServer(this, grpc, this);
        dirconServer.start();
        updateDirconStatus(
                dirconServer.isTreadmillProfileActive()
                        ? ServiceStatusSnapshot.DirconProfile.KICKR_RUN
                        : ServiceStatusSnapshot.DirconProfile.GENERIC,
                dirconServer.getCurrentServiceName()
        );

        startNotificationLoop();

        runtimeStarted = true;
        backendState = ServiceStatusSnapshot.BackendState.READY;
        bleState = ServiceStatusSnapshot.BleState.ACTIVE;
        transitionToState(ServiceStartupState.RUNNING);
        clearRecoveredStatusError();
        logger.info(this, "runtime_ready", "FTMS runtime is ready");
        refreshStatusSnapshot();
    }

    private void stopRuntimeComponents() {
        runtimeStarted = false;
        controlGranted = false;
        subscribedDevices.clear();

        stopNotificationLoop();
        stopAdvertising();
        closeGattServer();
        bleState = BluetoothPermissionGate.hasRequiredPermissions(this)
                ? ServiceStatusSnapshot.BleState.STOPPED
                : ServiceStatusSnapshot.BleState.WAITING_FOR_PERMISSION;

        if (dirconServer != null) {
            dirconServer.stop();
            dirconServer = null;
        }
        dirconProfile = ServiceStatusSnapshot.DirconProfile.DISABLED;
        dirconServiceName = "Unavailable";
        refreshStatusSnapshot();
    }

    private void handleMissingPermissions(String source) {
        boolean bleExposed = isBleExposed();
        boolean dirconExposed = isDirconExposed();

        logger.warn(
                this,
                "bluetooth_permissions_missing",
                "Bluetooth permissions missing during " + source + ": "
                        + BluetoothPermissionGate.describeMissingPermissions(this)
        );

        recordMissingPermissionsIfNeeded(source, bleExposed, dirconExposed);
        stopRuntimeComponents();
        if (grpc != null) {
            grpc.disconnect();
        }
        synchronized (startupLock) {
            cancelPendingStartupLocked();
            grpcRetryCount = 0;
        }
        backendState = ServiceStatusSnapshot.BackendState.DISCONNECTED;
        bleState = ServiceStatusSnapshot.BleState.WAITING_FOR_PERMISSION;
        transitionToState(ServiceStartupState.WAITING_FOR_PERMISSION);
        refreshStatusSnapshot();
    }

    private void transitionToState(ServiceStartupState newState) {
        if (startupState == newState) {
            updateForegroundNotification();
            refreshStatusSnapshot();
            return;
        }

        startupState = newState;
        updateForegroundNotification();
        refreshStatusSnapshot();
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

    private void forceBackendRetry(String source) {
        logger.info(this, "manual_backend_retry", "Manual backend retry requested from " + source);
        stopRuntimeComponents();
        if (grpc != null) {
            grpc.disconnect();
        }
        synchronized (startupLock) {
            cancelPendingStartupLocked();
            grpcRetryCount = 0;
        }
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions(source);
            return;
        }
        scheduleGrpcStartup(0L);
    }

    private void restartBluetoothPeripheral(String source) {
        logger.info(this, "manual_bluetooth_restart", "Manual Bluetooth restart requested from " + source);
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions(source);
            return;
        }
        stopRuntimeComponents();
        if (grpc != null && grpc.isConnected()) {
            handler.post(this::startRuntimeComponents);
        } else {
            forceBackendRetry(source);
        }
    }

    // --- Bluetooth Adapter State ---

    private void registerBluetoothStateReceiver() {
        bluetoothStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    logger.warn(FTMSService.this, "bluetooth_adapter_off",
                            "Bluetooth adapter turned off; tearing down BLE components");
                    stopRuntimeComponents();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    logger.info(FTMSService.this, "bluetooth_adapter_on",
                            "Bluetooth adapter turned on; restarting");
                    if (BluetoothPermissionGate.hasRequiredPermissions(FTMSService.this)) {
                        if (grpc != null && grpc.isConnected()) {
                            handler.post(() -> startRuntimeComponents());
                        } else {
                            forceBackendRetry("bluetooth_adapter_on");
                        }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStateReceiver, filter);
    }

    private void unregisterBluetoothStateReceiver() {
        if (bluetoothStateReceiver != null) {
            try {
                unregisterReceiver(bluetoothStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bluetoothStateReceiver = null;
        }
    }

    private void handlePreferencesChanged() {
        NordicFtmsPreferences.syncStatusSnapshot(this);
        if (dirconServer != null) {
            dirconServer.refreshProfileNow();
            updateDirconStatus(
                    dirconServer.isTreadmillProfileActive()
                            ? ServiceStatusSnapshot.DirconProfile.KICKR_RUN
                            : ServiceStatusSnapshot.DirconProfile.GENERIC,
                    dirconServer.getCurrentServiceName()
            );
        }
        refreshStatusSnapshot();
    }

    private void refreshStatusSnapshot() {
        NordicFtmsPreferences.syncStatusSnapshot(this);
        final ServiceStatusSnapshot.BluetoothPermissionState permissionState =
                BluetoothPermissionGate.getPermissionState(this);
        final int reconnectAttempt = grpcRetryCount;
        final ServiceStatusSnapshot.ConsoleSummary consoleSummary = new ServiceStatusSnapshot.ConsoleSummary();
        if (grpc != null && grpc.getConsoleInfo() != null) {
            consoleSummary.applyConsoleInfo(grpc.getConsoleInfo());
        }

        NordicFtmsStatusStore.getInstance().update(snapshot -> {
            snapshot.serviceState = startupState;
            snapshot.bluetoothPermissionState = permissionState;
            snapshot.bleState = bleState;
            snapshot.backendState = backendState;
            snapshot.dirconProfile = dirconProfile;
            snapshot.advertisedFtmsName = ADVERTISED_DEVICE_NAME;
            snapshot.dirconServiceName = dirconServiceName;
            snapshot.reconnectAttempt = reconnectAttempt;
            snapshot.consoleSummary = consoleSummary;
            if (grpc != null) {
                snapshot.liveMetrics.speedKph = grpc.getLastSpeedKph();
                snapshot.liveMetrics.inclinePercent = grpc.getLastInclinePercent();
                snapshot.liveMetrics.distanceKm = grpc.getLastDistanceKm();
                snapshot.liveMetrics.resistance = grpc.getLastResistance();
                snapshot.liveMetrics.cadenceRpm = grpc.getLastCadenceRpm();
                snapshot.liveMetrics.watts = grpc.getLastWatts();
            }
        });
    }

    private void clearRecoveredStatusError() {
        if (startupState == ServiceStartupState.RUNNING
                && backendState == ServiceStatusSnapshot.BackendState.READY
                && bleState == ServiceStatusSnapshot.BleState.ACTIVE) {
            NordicFtmsStatusStore.getInstance().clearLastError();
        }
    }

    void onDirconAdvertisementUpdated(boolean treadmillProfile, String serviceName) {
        updateDirconStatus(
                treadmillProfile
                        ? ServiceStatusSnapshot.DirconProfile.KICKR_RUN
                        : ServiceStatusSnapshot.DirconProfile.GENERIC,
                serviceName
        );
    }

    private void updateDirconStatus(
            ServiceStatusSnapshot.DirconProfile newProfile,
            String newServiceName
    ) {
        dirconProfile = newProfile;
        dirconServiceName = newServiceName == null || newServiceName.isEmpty()
                ? "Unavailable"
                : newServiceName;
        refreshStatusSnapshot();
    }

    private void cancelPendingStartupLocked() {
        startupTaskScheduled = false;
        if (startupFuture != null) {
            startupFuture.cancel(false);
            startupFuture = null;
        }
    }

    // --- GATT Server ---

    private boolean setupGattServer() {
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            logger.warn(this, "gatt_permissions_missing", "Skipping GATT server setup because Bluetooth permissions are missing");
            return false;
        }

        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            logger.warn(this, "bluetooth_unavailable", "Bluetooth not available or not enabled");
            return false;
        }

        closeGattServer();
        gattServer = openGattServerSafely();
        if (gattServer == null) {
            return false;
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

        if (!addGattServiceSafely(ftmsService)) {
            closeGattServer();
            return false;
        }
        logger.info(this, "gatt_ready", "GATT server setup complete with FTMS service");
        return true;
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
                logger.info(FTMSService.this, "ble_device_connected", "Device connected: " + safeDeviceLabel(device));
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                logger.info(FTMSService.this, "ble_device_disconnected", "Device disconnected: " + safeDeviceLabel(device));
                subscribedDevices.remove(device);
                controlGranted = false;
            }
            refreshStatusSnapshot();
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
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_SPEED_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minSpeed = (int) (getMinSpeedKph() * 100);
                int maxSpeed = (int) (getMaxSpeedKph() * 100);
                writeUint16LE(value, 0, minSpeed);
                writeUint16LE(value, 2, maxSpeed);
                writeUint16LE(value, 4, 10);
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_INCLINATION_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minIncline = (int) (getMinInclinePercent() * 10);
                int maxIncline = (int) (getMaxInclinePercent() * 10);
                writeInt16LE(value, 0, minIncline);
                writeInt16LE(value, 2, maxIncline);
                writeUint16LE(value, 4, 5);
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else if (uuid.equals(SUPPORTED_RESISTANCE_RANGE_UUID)) {
                byte[] value = new byte[6];
                int minRes = (int) (getMinResistance() * 10);
                int maxRes = (int) (getMaxResistance() * 10);
                writeUint16LE(value, 0, minRes);
                writeUint16LE(value, 2, maxRes);
                writeUint16LE(value, 4, 10);
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));

            } else {
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
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
                    sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
                controlPointCharacteristic.setValue(response);
                notifyCharacteristicChangedSafely(device, controlPointCharacteristic, true);
            } else if (responseNeeded) {
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
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
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            } else {
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
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
                    logger.info(FTMSService.this, "ble_device_subscribed", "Device subscribed: " + safeDeviceLabel(device));
                } else {
                    subscribedDevices.remove(device);
                    logger.info(FTMSService.this, "ble_device_unsubscribed", "Device unsubscribed: " + safeDeviceLabel(device));
                }
                if (responseNeeded) {
                    sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            } else if (responseNeeded) {
                sendGattResponseSafely(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }
    };

    public void setFtmsTargetIncline(double incline) {
        long nowMs = SystemClock.elapsedRealtime();
        inclineCommandTracker.setTargetIncline(incline, nowMs);
        logger.trace(this, "tracker_command_ftms",
                String.format(java.util.Locale.US,
                        "incline=%.2f%% pending=%d oldestAgeMs=%d",
                        incline,
                        inclineCommandTracker.getPendingCount(),
                        inclineCommandTracker.getOldestPendingAgeMs(nowMs)));
    }

    // --- Control Point ---

    private byte[] handleControlPoint(byte[] value) {
        if (value == null || value.length < 1) {
            return new byte[]{OP_RESPONSE_CODE, 0x00, RESULT_NOT_SUPPORTED};
        }

        byte opcode = value[0];
        logger.info(this, "control_point_opcode", "Control Point opcode: 0x" + String.format("%02X", opcode));

        switch (opcode) {
            case OP_REQUEST_CONTROL:
                controlGranted = true;
                logger.info(this, "control_granted", "Control granted");
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};

            case OP_RESET:
                controlGranted = false;
                logger.info(this, "control_reset", "Reset received");
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};

            case OP_SET_TARGET_SPEED:
                if (!controlGranted) {
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_CONTROL_NOT_PERMITTED};
                }
                if (value.length >= 3) {
                    int speedRaw = readUint16LE(value, 1);
                    double speedKmh = speedRaw / 100.0;
                    logger.info(this, "set_target_speed", "Set target speed: " + speedKmh + " km/h (via gRPC)");
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
                    logger.info(this, "set_target_incline", "Set target inclination: " + inclination + "% (via gRPC)");
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
                    logger.info(this, "set_target_resistance", "Set target resistance: " + resistance + " (via gRPC)");
                    if (grpc != null) {
                        grpc.setResistance(resistance);
                    }
                    return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_SUCCESS};
                }
                return new byte[]{OP_RESPONSE_CODE, opcode, RESULT_NOT_SUPPORTED};

            default:
                logger.warn(this, "unsupported_control_opcode", "Unsupported opcode: 0x" + String.format("%02X", opcode));
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

    private boolean startAdvertising() {
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            logger.warn(this, "advertising_permissions_missing", "Skipping BLE advertising because Bluetooth permissions are missing");
            return false;
        }

        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null) {
            logger.warn(this, "advertising_adapter_missing", "No Bluetooth adapter");
            return false;
        }

        if (!setBluetoothNameSafely(adapter)) {
            return false;
        }

        advertiser = getBluetoothLeAdvertiserSafely(adapter);
        if (advertiser == null) {
            logger.warn(this, "advertiser_unavailable", "BLE advertising not supported");
            return false;
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

        if (!startAdvertisingSafely(settings, data, scanResponse)) {
            advertiser = null;
            return false;
        }
        logger.info(this, "advertising_started", "Started BLE advertising as \"" + ADVERTISED_DEVICE_NAME + "\"");
        return true;
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (SecurityException e) {
                logger.warn(this, "advertising_stop_permission_error", "Ignoring Bluetooth permission error while stopping advertising", e);
            } catch (Exception e) {
                logger.error(this, "advertising_stop_error", "Error stopping advertising", e);
            }
            advertiser = null;
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            bleState = ServiceStatusSnapshot.BleState.ACTIVE;
            logger.info(FTMSService.this, "advertising_start_success", "BLE advertising started successfully");
            refreshStatusSnapshot();
        }

        @Override
        public void onStartFailure(int errorCode) {
            bleState = ServiceStatusSnapshot.BleState.ERROR;
            logger.warn(FTMSService.this, "advertising_start_failure", "BLE advertising failed with error code: " + errorCode);
            refreshStatusSnapshot();
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
                refreshStatusSnapshot();
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

        GrpcControlService g = grpc;
        if (gattServer == null || subscribedDevices.isEmpty() || g == null || !g.isConnected()) {
            return;
        }

        checkForManualInclineChange(g);

        boolean isBike = g.isBikeDevice();

        for (BluetoothDevice device : subscribedDevices) {
            try {
                if (isBike) {
                    byte[] bikeData = buildIndoorBikeData(g);
                    indoorBikeDataCharacteristic.setValue(bikeData);
                    notifyCharacteristicChangedSafely(device, indoorBikeDataCharacteristic, false);
                } else {
                    byte[] treadmillData = buildTreadmillData(g);
                    treadmillDataCharacteristic.setValue(treadmillData);
                    notifyCharacteristicChangedSafely(device, treadmillDataCharacteristic, false);
                }
            } catch (Exception e) {
                logger.error(this, "notification_send_error", "Error sending notification to " + safeDeviceLabel(device), e);
            }
        }
    }

    private void checkForManualInclineChange(GrpcControlService g) {
        double currentIncline = g.getLastInclinePercent();
        long nowMs = SystemClock.elapsedRealtime();
        double closestPending = inclineCommandTracker.getClosestPendingValue(currentIncline);
        double closestDelta = inclineCommandTracker.getClosestPendingDelta(currentIncline);
        int pendingCount = inclineCommandTracker.getPendingCount();
        long msSinceLastCmd = inclineCommandTracker.getMsSinceLastCommand(nowMs);

        InclineCommandTracker.ChangeResult changeResult = inclineCommandTracker.updateObservedIncline(
                currentIncline,
                nowMs
        );

        logger.trace(this, "tracker_check_ftms",
                String.format(java.util.Locale.US,
                        "observed=%.2f%% closestPending=%.2f%% delta=%.3f%% pending=%d msSinceCmd=%d result=%s",
                        currentIncline,
                        closestPending,
                        closestDelta,
                        pendingCount,
                        msSinceLastCmd,
                        changeResult));

        if (changeResult != InclineCommandTracker.ChangeResult.MANUAL_OVERRIDE) {
            return;
        }

        int inclRaw = (int) (currentIncline * 10);
        byte[] status = new byte[3];
        status[0] = 0x06;
        writeInt16LE(status, 1, inclRaw);

        logger.info(this, "manual_incline_detected",
                String.format(java.util.Locale.US,
                        "Manual incline change detected: observed=%.2f%% closestPending=%.2f%% delta=%.3f%% pending=%d msSinceCmd=%d",
                        currentIncline, closestPending, closestDelta, pendingCount, msSinceLastCmd));

        for (BluetoothDevice device : subscribedDevices) {
            try {
                machineStatusCharacteristic.setValue(status);
                notifyCharacteristicChangedSafely(device, machineStatusCharacteristic, false);
            } catch (Exception e) {
                logger.error(this, "machine_status_send_error", "Error sending Machine Status to " + safeDeviceLabel(device), e);
            }
        }

        if (dirconServer != null) {
            dirconServer.sendMachineStatusToAll(status);
        }
    }

    // --- Data Builders ---

    private byte[] buildTreadmillData(GrpcControlService g) {
        byte[] data = new byte[11];

        writeUint16LE(data, 0, 0x000C);

        int speed = (int) (g.getLastSpeedKph() * 100);
        if (speed < 0) {
            speed = 0;
        }
        writeUint16LE(data, 2, speed);

        int distMeters = (int) (g.getLastDistanceKm() * 1000);
        if (distMeters < 0) {
            distMeters = 0;
        }
        data[4] = (byte) (distMeters & 0xFF);
        data[5] = (byte) ((distMeters >> 8) & 0xFF);
        data[6] = (byte) ((distMeters >> 16) & 0xFF);

        int inclination = (int) (g.getLastInclinePercent() * 10);
        writeInt16LE(data, 7, inclination);
        writeInt16LE(data, 9, 0);

        return data;
    }

    private byte[] buildIndoorBikeData(GrpcControlService g) {
        byte[] data = new byte[10];

        writeUint16LE(data, 0, 0x0064);

        int speed = (int) (g.getLastSpeedKph() * 100);
        if (speed < 0) {
            speed = 0;
        }
        writeUint16LE(data, 2, speed);

        int cadence = (int) (g.getLastCadenceRpm() * 2);
        writeUint16LE(data, 4, cadence);

        int resistance = (int) (g.getLastResistance() * 10);
        writeInt16LE(data, 6, resistance);

        int power = (int) g.getLastWatts();
        writeInt16LE(data, 8, power);

        return data;
    }

    // --- Cleanup ---

    private void closeGattServer() {
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (SecurityException e) {
                logger.warn(this, "gatt_close_permission_error", "Ignoring Bluetooth permission error while closing the GATT server", e);
            } catch (Exception e) {
                logger.error(this, "gatt_close_error", "Error closing GATT server", e);
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
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            handleBluetoothSecurityException("device_address", e);
            return "permission-blocked-device";
        }
    }

    private BluetoothGattServer openGattServerSafely() {
        try {
            return bluetoothManager != null ? bluetoothManager.openGattServer(this, gattServerCallback) : null;
        } catch (SecurityException e) {
            handleBluetoothSecurityException("open_gatt_server", e);
            return null;
        } catch (Exception e) {
            logger.error(this, "open_gatt_server_error", "Failed to open GATT server", e);
            return null;
        }
    }

    private boolean addGattServiceSafely(BluetoothGattService service) {
        if (gattServer == null) {
            return false;
        }
        try {
            return gattServer.addService(service);
        } catch (SecurityException e) {
            handleBluetoothSecurityException("add_gatt_service", e);
            return false;
        } catch (Exception e) {
            logger.error(this, "add_gatt_service_error", "Failed to add the FTMS GATT service", e);
            return false;
        }
    }

    private void sendGattResponseSafely(
            BluetoothDevice device,
            int requestId,
            int status,
            int offset,
            byte[] value
    ) {
        if (gattServer == null) {
            return;
        }
        try {
            gattServer.sendResponse(device, requestId, status, offset, value);
        } catch (SecurityException e) {
            handleBluetoothSecurityException("send_gatt_response", e);
        } catch (Exception e) {
            logger.error(this, "send_gatt_response_error", "Failed to send a GATT response", e);
        }
    }

    private void notifyCharacteristicChangedSafely(
            BluetoothDevice device,
            BluetoothGattCharacteristic characteristic,
            boolean confirm
    ) {
        if (gattServer == null || characteristic == null) {
            return;
        }
        try {
            gattServer.notifyCharacteristicChanged(device, characteristic, confirm);
        } catch (SecurityException e) {
            handleBluetoothSecurityException("notify_characteristic_changed", e);
        } catch (Exception e) {
            logger.error(this, "notify_characteristic_error", "Failed to notify a Bluetooth client", e);
        }
    }

    private boolean setBluetoothNameSafely(BluetoothAdapter adapter) {
        try {
            return adapter.setName(ADVERTISED_DEVICE_NAME);
        } catch (SecurityException e) {
            handleBluetoothSecurityException("set_adapter_name", e);
            return false;
        } catch (Exception e) {
            logger.error(this, "set_adapter_name_error", "Failed to set the Bluetooth adapter name", e);
            return false;
        }
    }

    private BluetoothLeAdvertiser getBluetoothLeAdvertiserSafely(BluetoothAdapter adapter) {
        try {
            return adapter.getBluetoothLeAdvertiser();
        } catch (SecurityException e) {
            handleBluetoothSecurityException("get_bluetooth_le_advertiser", e);
            return null;
        } catch (Exception e) {
            logger.error(this, "get_bluetooth_le_advertiser_error", "Failed to get the Bluetooth LE advertiser", e);
            return null;
        }
    }

    private boolean startAdvertisingSafely(
            AdvertiseSettings settings,
            AdvertiseData data,
            AdvertiseData scanResponse
    ) {
        if (advertiser == null) {
            return false;
        }
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
            return true;
        } catch (SecurityException e) {
            handleBluetoothSecurityException("start_advertising", e);
            return false;
        } catch (Exception e) {
            logger.error(this, "start_advertising_error", "Failed to start BLE advertising", e);
            return false;
        }
    }

    private void handleBluetoothSecurityException(String source, SecurityException error) {
        logger.error(this, "bluetooth_security_exception", "Bluetooth call failed during " + source, error);
        if (!BluetoothPermissionGate.hasRequiredPermissions(this)) {
            handleMissingPermissions(source);
        } else {
            bleState = ServiceStatusSnapshot.BleState.ERROR;
            refreshStatusSnapshot();
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
        if (val >= 0x8000) {
            val -= 0x10000;
        }
        return val;
    }
}
