package com.nordicftms.app;

import android.content.Context;
import com.ifit.glassos.CadenceData;
import com.ifit.glassos.CadenceServiceGrpc;
import com.ifit.glassos.ConsoleInfo;
import com.ifit.glassos.ConsoleServiceGrpc;
import com.ifit.glassos.ConsoleStateResponse;
import com.ifit.glassos.ConsoleType;
import com.ifit.glassos.DistanceData;
import com.ifit.glassos.DistanceServiceGrpc;
import com.ifit.glassos.Empty;
import com.ifit.glassos.InclineData;
import com.ifit.glassos.InclineServiceGrpc;
import com.ifit.glassos.ResistanceData;
import com.ifit.glassos.ResistanceServiceGrpc;
import com.ifit.glassos.Result;
import com.ifit.glassos.SetInclineRequest;
import com.ifit.glassos.SetResistanceRequest;
import com.ifit.glassos.SetSpeedRequest;
import com.ifit.glassos.SpeedData;
import com.ifit.glassos.SpeedServiceGrpc;
import com.ifit.glassos.WattsData;
import com.ifit.glassos.WattsServiceGrpc;
import com.ifit.glassos.WorkoutServiceGrpc;
import com.ifit.glassos.WorkoutStateResponse;

import java.io.InputStream;
import java.net.ConnectException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * gRPC client that connects to the GlassOS service on localhost:54321 with mTLS.
 * Uses certificates extracted from the GlassOS APK to authenticate as com.ifit.dev_app.
 */
public class GrpcControlService {
    public interface BackendUnavailableListener {
        void onGrpcBackendUnavailable(String source, Throwable error);
    }

    private enum DisconnectReason {
        NONE,
        LOCAL_REQUEST,
        CONNECT_RETRY,
        BACKEND_FAILURE
    }

    private static final String LOG_TAG = "GRPC";
    private static final String HOST = "localhost";
    private static final int PORT = 54321;
    private static final String CLIENT_ID = "com.ifit.dev_app";
    private static final int CONSOLE_INFO_FETCH_ATTEMPTS = 5;
    private static final long CONSOLE_INFO_FETCH_RETRY_MS = 1500L;
    private static final long CONSOLE_INFO_STREAM_RETRY_MS = 3000L;

    private static final Metadata.Key<String> CLIENT_ID_KEY =
            Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER);

    private final Context appContext;
    private final BackendUnavailableListener backendUnavailableListener;
    private final Object consoleInfoLock = new Object();
    private final AtomicBoolean backendUnavailableReported = new AtomicBoolean(false);
    private final ScheduledExecutorService callbackExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong generationCounter = new AtomicLong(0L);
    private final NordicFtmsLogger logger = NordicFtmsLogger.getInstance();

    private ManagedChannel channel;

    // Blocking stubs for control commands
    private SpeedServiceGrpc.SpeedServiceBlockingStub speedStub;
    private InclineServiceGrpc.InclineServiceBlockingStub inclineStub;
    private ResistanceServiceGrpc.ResistanceServiceBlockingStub resistanceStub;
    private WorkoutServiceGrpc.WorkoutServiceBlockingStub workoutStub;
    private ConsoleServiceGrpc.ConsoleServiceBlockingStub consoleStub;

    // Async stubs for streaming subscriptions
    private SpeedServiceGrpc.SpeedServiceStub speedAsyncStub;
    private InclineServiceGrpc.InclineServiceStub inclineAsyncStub;
    private DistanceServiceGrpc.DistanceServiceStub distanceAsyncStub;
    private ResistanceServiceGrpc.ResistanceServiceStub resistanceAsyncStub;
    private CadenceServiceGrpc.CadenceServiceStub cadenceAsyncStub;
    private WattsServiceGrpc.WattsServiceStub wattsAsyncStub;
    private ConsoleServiceGrpc.ConsoleServiceStub consoleAsyncStub;
    private WorkoutServiceGrpc.WorkoutServiceStub workoutAsyncStub;

    // Latest values from subscriptions
    private volatile double lastSpeedKph = 0;
    private volatile double lastInclinePercent = 0;
    private volatile double lastDistanceKm = 0;
    private volatile double lastResistance = 0;
    private volatile double lastCadenceRpm = 0;
    private volatile double lastWatts = 0;

    // Console info (populated once on connect)
    private volatile ConsoleInfo consoleInfo;
    private volatile ConsoleType machineType = ConsoleType.CONSOLE_TYPE_UNKNOWN;
    private volatile ConsoleInfo cachedConsoleInfo;
    private volatile ConsoleType cachedMachineType = ConsoleType.CONSOLE_TYPE_UNKNOWN;
    private volatile boolean connected = false;
    private volatile boolean advancedSubscriptionsStarted = false;
    private volatile boolean consoleInfoSubscriptionStarted = false;
    private volatile Throwable lastConsoleInfoFetchError;
    private volatile String lastConsoleInfoFetchErrorSource = "unknown";
    private volatile Throwable lastConnectionError;
    private volatile String lastConnectionErrorSource = "unknown";
    private volatile long activeGeneration = 0L;
    private volatile DisconnectReason disconnectReason = DisconnectReason.NONE;
    private volatile double cachedSpeedKph = 0.0;
    private volatile double cachedInclinePercent = 0.0;
    private volatile double cachedDistanceKm = 0.0;
    private volatile double cachedResistance = 0.0;
    private volatile double cachedCadenceRpm = 0.0;
    private volatile double cachedWatts = 0.0;

    public GrpcControlService(Context context, BackendUnavailableListener listener) {
        this.appContext = context.getApplicationContext();
        this.backendUnavailableListener = listener;
    }

    public synchronized boolean connect() {
        resetConnectionErrors();
        disconnectInternal(false, DisconnectReason.LOCAL_REQUEST);
        long generation = generationCounter.incrementAndGet();
        activeGeneration = generation;
        disconnectReason = DisconnectReason.NONE;

        try {
            SSLSocketFactory sslSocketFactory = createSslSocketFactory();

            Metadata headers = new Metadata();
            headers.put(CLIENT_ID_KEY, CLIENT_ID);

            channel = OkHttpChannelBuilder.forAddress(HOST, PORT)
                    .sslSocketFactory(sslSocketFactory)
                    .overrideAuthority("localhost")
                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .build();

            speedStub = SpeedServiceGrpc.newBlockingStub(channel);
            inclineStub = InclineServiceGrpc.newBlockingStub(channel);
            resistanceStub = ResistanceServiceGrpc.newBlockingStub(channel);
            workoutStub = WorkoutServiceGrpc.newBlockingStub(channel);
            consoleStub = ConsoleServiceGrpc.newBlockingStub(channel);

            speedAsyncStub = SpeedServiceGrpc.newStub(channel);
            inclineAsyncStub = InclineServiceGrpc.newStub(channel);
            distanceAsyncStub = DistanceServiceGrpc.newStub(channel);
            resistanceAsyncStub = ResistanceServiceGrpc.newStub(channel);
            cadenceAsyncStub = CadenceServiceGrpc.newStub(channel);
            wattsAsyncStub = WattsServiceGrpc.newStub(channel);
            consoleAsyncStub = ConsoleServiceGrpc.newStub(channel);
            workoutAsyncStub = WorkoutServiceGrpc.newStub(channel);

            // Subscribe before readiness is decided so we do not miss the
            // initial ConsoleChanged push on GlassOS builds that publish the
            // real console capabilities shortly after transport startup.
            subscribeConsoleInfo(generation);

            if (!resolveInitialConsoleInfo(generation)) {
                lastConnectionError = lastConsoleInfoFetchError != null
                        ? lastConsoleInfoFetchError
                        : new IllegalStateException("Initial console info fetch did not return usable data");
                lastConnectionErrorSource = lastConsoleInfoFetchErrorSource;
                logger.warn(appContext, "grpc_backend_not_ready", "gRPC transport created but backend is not ready yet via "
                        + lastConnectionErrorSource, lastConnectionError);
                disconnectInternal(false, DisconnectReason.CONNECT_RETRY);
                return false;
            }

            connected = true;
            backendUnavailableReported.set(false);
            disconnectReason = DisconnectReason.NONE;
            logger.info(appContext, "grpc_ready", "gRPC backend ready on " + HOST + ":" + PORT + " with mTLS");
            return true;

        } catch (Exception e) {
            lastConnectionError = e;
            lastConnectionErrorSource = "connect";
            logger.error(appContext, "grpc_connect_error", "Failed to connect gRPC backend", e);
            disconnectInternal(false, DisconnectReason.CONNECT_RETRY);
            return false;
        }
    }

    public synchronized void disconnect() {
        disconnectInternal(true, DisconnectReason.LOCAL_REQUEST);
        backendUnavailableReported.set(false);
    }

    public synchronized void shutdown() {
        disconnect();
        callbackExecutor.shutdownNow();
    }

    public boolean isConnected() {
        return connected;
    }

    public Throwable getLastConnectionError() {
        return lastConnectionError;
    }

    public String getLastConnectionErrorSource() {
        return lastConnectionErrorSource;
    }

    // --- Console Info ---

    private boolean resolveInitialConsoleInfo(long generation) {
        lastConsoleInfoFetchError = null;
        lastConsoleInfoFetchErrorSource = "initial_console_info";

        for (int attempt = 1; attempt <= CONSOLE_INFO_FETCH_ATTEMPTS; attempt++) {
            if (generation != activeGeneration) {
                return false;
            }
            if (hasUsableConsoleInfo()) {
                return true;
            }

            if (fetchConsoleInfoFromRpc(false, attempt, generation)) {
                return true;
            }
            if (fetchConsoleInfoFromRpc(true, attempt, generation)) {
                return true;
            }

            if (hasUsableConsoleInfo()) {
                return true;
            }

            if (attempt < CONSOLE_INFO_FETCH_ATTEMPTS) {
                logger.info(appContext, "console_info_retry", "Console info not ready after attempt " + attempt
                        + ", retrying in " + CONSOLE_INFO_FETCH_RETRY_MS + " ms");
                try {
                    Thread.sleep(CONSOLE_INFO_FETCH_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastConsoleInfoFetchError = e;
                    lastConsoleInfoFetchErrorSource = "initial_console_info_resolution";
                    return false;
                }
            }
        }

        return hasUsableConsoleInfo();
    }

    private boolean fetchConsoleInfoFromRpc(boolean useKnownConsoleInfo, int attempt, long generation) {
        String source = useKnownConsoleInfo ? "GetKnownConsoleInfo" : "GetConsole";

        try {
            ConsoleInfo fetchedConsoleInfo = useKnownConsoleInfo
                    ? consoleStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getKnownConsoleInfo(Empty.getDefaultInstance())
                    : consoleStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getConsole(Empty.getDefaultInstance());

            return applyConsoleInfo(source, fetchedConsoleInfo, attempt, generation);
        } catch (Exception e) {
            if (hasUsableConsoleInfo()) {
                logger.info(appContext, "console_info_fetch_ignored", "Console info fetch via " + source
                        + " failed on attempt " + attempt + " but usable console info is already cached");
                return true;
            }
            if (isBackendUnavailableSignal(e) || isChannelShutdownSignal(e)) {
                logger.info(appContext, "console_info_fetch_retryable", "Console info via " + source
                        + " is not ready yet on attempt " + attempt + ": " + summarizeTransportError(e));
            } else {
                logger.error(appContext, "console_info_fetch_error", "Failed to fetch console info via " + source
                        + " (attempt " + attempt + ")", e);
            }
            lastConsoleInfoFetchError = e;
            lastConsoleInfoFetchErrorSource = source;
            return false;
        }
    }

    private void subscribeConsoleInfo(long generation) {
        if (consoleAsyncStub == null || channel == null || consoleInfoSubscriptionStarted) {
            return;
        }

        consoleInfoSubscriptionStarted = true;
        consoleAsyncStub.consoleChanged(Empty.getDefaultInstance(), new StreamObserver<ConsoleInfo>() {
            @Override
            public void onNext(ConsoleInfo updatedConsoleInfo) {
                applyConsoleInfo("ConsoleChanged", updatedConsoleInfo, 0, generation);
            }

            @Override
            public void onError(Throwable t) {
                consoleInfoSubscriptionStarted = false;
                handleSubscriptionError(
                        "console_subscription",
                        "console_subscription_error",
                        "Console info subscription error",
                        t,
                        generation,
                        () -> subscribeConsoleInfo(generation),
                        CONSOLE_INFO_STREAM_RETRY_MS
                );
            }

            @Override
            public void onCompleted() {
                consoleInfoSubscriptionStarted = false;
                logger.info(appContext, "console_subscription_completed", "Console info subscription completed");
            }
        });
    }

    private boolean applyConsoleInfo(String source, ConsoleInfo updatedConsoleInfo, int attempt, long generation) {
        if (generation != activeGeneration) {
            return false;
        }
        if (updatedConsoleInfo == null) {
            return false;
        }

        boolean infoUsable = isConsoleInfoUsable(updatedConsoleInfo);
        boolean updatedMachineType = false;

        synchronized (consoleInfoLock) {
            ConsoleInfo previousConsoleInfo = consoleInfo;
            boolean previousInfoUsable = isConsoleInfoUsable(previousConsoleInfo);

            if (previousConsoleInfo != null && previousConsoleInfo.equals(updatedConsoleInfo)) {
                return infoUsable;
            }

            if (!infoUsable && previousInfoUsable) {
                logger.warn(appContext, "console_info_empty_ignored", "Ignoring empty console info from " + source
                        + " because populated console info is already cached");
                return false;
            }

            ConsoleType previousMachineType = machineType;
            consoleInfo = updatedConsoleInfo;
            machineType = updatedConsoleInfo.getMachineType();
            cachedConsoleInfo = updatedConsoleInfo;
            cachedMachineType = updatedConsoleInfo.getMachineType();
            updatedMachineType = previousMachineType != machineType;
        }

        if (infoUsable) {
            lastConsoleInfoFetchError = null;
            lastConsoleInfoFetchErrorSource = "unknown";
        }
        logConsoleInfo(source, updatedConsoleInfo, attempt, infoUsable);
        SentryDiagnostics.recordConsoleInfo(
                updatedConsoleInfo,
                updatedConsoleInfo.getMachineType(),
                source,
                infoUsable,
                attempt
        );

        if (updatedMachineType) {
            startEquipmentSpecificSubscriptionsIfNeeded();
        }

        return infoUsable;
    }

    private void logConsoleInfo(String source, ConsoleInfo updatedConsoleInfo, int attempt, boolean infoUsable) {
        String attemptSuffix = attempt > 0 ? " (attempt " + attempt + ")" : "";

        logger.info(appContext, "console_info_received", "Console info received via " + source + attemptSuffix
                + " usable=" + infoUsable);
        logger.info(appContext, "console_info_details",
                "Machine type=" + updatedConsoleInfo.getMachineType()
                        + " name=" + updatedConsoleInfo.getName()
                        + " speedRange=" + updatedConsoleInfo.getMinKph() + "-" + updatedConsoleInfo.getMaxKph()
                        + " inclineRange=" + updatedConsoleInfo.getMinInclinePercent() + "-" + updatedConsoleInfo.getMaxInclinePercent()
                        + " canSetSpeed=" + updatedConsoleInfo.getCanSetSpeed()
                        + " canSetIncline=" + updatedConsoleInfo.getCanSetIncline()
                        + " canSetResistance=" + updatedConsoleInfo.getCanSetResistance()
                        + " firmware=" + updatedConsoleInfo.getFirmwareVersion()
                        + " serial=" + updatedConsoleInfo.getProductSerialNumber());
        NordicFtmsStatusStore.getInstance().setConsoleInfo(updatedConsoleInfo);
    }

    private boolean hasUsableConsoleInfo() {
        return isConsoleInfoUsable(consoleInfo);
    }

    private boolean isConsoleInfoUsable(ConsoleInfo info) {
        if (info == null) {
            return false;
        }

        return info.getMachineType() != ConsoleType.CONSOLE_TYPE_UNKNOWN
                || !info.getName().isEmpty()
                || info.getCanSetSpeed()
                || info.getCanSetIncline()
                || info.getCanSetResistance()
                || info.getMaxKph() > 0.0
                || info.getMaxInclinePercent() > 0.0
                || info.getMaxResistance() > 0.0
                || !info.getFirmwareVersion().isEmpty()
                || !info.getProductSerialNumber().isEmpty();
    }

    private boolean isTreadmillLikeConsole(ConsoleInfo info) {
        if (info == null) {
            return false;
        }

        boolean hasSpeedControl = info.getCanSetSpeed() || info.getMaxKph() > 0.0;
        boolean hasInclineControl = info.getCanSetIncline() || info.getMaxInclinePercent() > 0.0;
        boolean hasBikeOrEllipticalControls = info.getCanSetResistance()
                || info.getMaxResistance() > 0.0
                || info.getCanSetGear()
                || info.getMaxGear() > 0;

        return hasSpeedControl && hasInclineControl && !hasBikeOrEllipticalControls;
    }

    public ConsoleInfo getConsoleInfo() {
        return consoleInfo != null ? consoleInfo : cachedConsoleInfo;
    }

    public ConsoleType getMachineType() {
        return machineType != ConsoleType.CONSOLE_TYPE_UNKNOWN ? machineType : cachedMachineType;
    }

    public boolean isBikeDevice() {
        ConsoleType currentMachineType = getMachineType();
        return currentMachineType == ConsoleType.BIKE
                || currentMachineType == ConsoleType.SPIN_BIKE;
    }

    public boolean isTreadmillDevice() {
        ConsoleType currentMachineType = getMachineType();
        return currentMachineType == ConsoleType.TREADMILL
                || currentMachineType == ConsoleType.INCLINE_TRAINER
                || (currentMachineType == ConsoleType.CONSOLE_TYPE_UNKNOWN
                && isTreadmillLikeConsole(getConsoleInfo()));
    }

    public boolean isRower() {
        return getMachineType() == ConsoleType.ROWER;
    }

    public boolean isElliptical() {
        ConsoleType currentMachineType = getMachineType();
        return currentMachineType == ConsoleType.ELLIPTICAL
                || currentMachineType == ConsoleType.VERTICAL_ELLIPTICAL
                || currentMachineType == ConsoleType.STRIDER
                || currentMachineType == ConsoleType.FREE_STRIDER;
    }

    // --- Control Commands ---

    public void setSpeed(double kph) {
        if (!connected) {
            return;
        }

        long generation = activeGeneration;
        callbackExecutor.execute(() -> {
            try {
                if (generation != activeGeneration || !connected || speedStub == null) {
                    return;
                }
                SetSpeedRequest req = SetSpeedRequest.newBuilder().setKph(kph).build();
                Result result = speedStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setSpeed(req);
                logger.info(appContext, "grpc_set_speed", "SetSpeed(" + kph + " kph) -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                handleCommandFailure("set_speed", "grpc_set_speed_failed", "SetSpeed failed", e, generation);
            } catch (Exception e) {
                logger.error(appContext, "grpc_set_speed_error", "SetSpeed error", e);
            }
        });
    }

    public void setIncline(double percent) {
        if (!connected) {
            return;
        }

        long generation = activeGeneration;
        callbackExecutor.execute(() -> {
            try {
                if (generation != activeGeneration || !connected || inclineStub == null) {
                    return;
                }
                SetInclineRequest req = SetInclineRequest.newBuilder().setPercent(percent).build();
                long sentAtMs = android.os.SystemClock.elapsedRealtime();
                Result result = inclineStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setIncline(req);
                long rttMs = android.os.SystemClock.elapsedRealtime() - sentAtMs;
                logger.info(appContext, "grpc_set_incline", "SetIncline(" + percent + "%) -> success=" + result.getSuccess());
                logger.trace(appContext, "grpc_set_incline_rtt",
                        String.format(java.util.Locale.US,
                                "percent=%.2f%% success=%s rttMs=%d",
                                percent, result.getSuccess(), rttMs));
            } catch (StatusRuntimeException e) {
                handleCommandFailure("set_incline", "grpc_set_incline_failed", "SetIncline failed", e, generation);
            } catch (Exception e) {
                logger.error(appContext, "grpc_set_incline_error", "SetIncline error", e);
            }
        });
    }

    public void setResistance(double resistance) {
        if (!connected) {
            return;
        }

        long generation = activeGeneration;
        callbackExecutor.execute(() -> {
            try {
                if (generation != activeGeneration || !connected || resistanceStub == null) {
                    return;
                }
                SetResistanceRequest req = SetResistanceRequest.newBuilder().setResistance(resistance).build();
                Result result = resistanceStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setResistance(req);
                logger.info(appContext, "grpc_set_resistance", "SetResistance(" + resistance + ") -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                handleCommandFailure("set_resistance", "grpc_set_resistance_failed", "SetResistance failed", e, generation);
            } catch (Exception e) {
                logger.error(appContext, "grpc_set_resistance_error", "SetResistance error", e);
            }
        });
    }

    // --- Data Subscriptions ---

    public void startSubscriptions() {
        if (!connected) {
            return;
        }

        long generation = activeGeneration;
        logger.info(appContext, "grpc_start_subscriptions", "Starting gRPC data subscriptions");
        subscribeConsoleInfo(generation);
        subscribeSpeed(generation);
        subscribeIncline(generation);
        subscribeDistance(generation);
        startEquipmentSpecificSubscriptionsIfNeeded();

        // Opt-in diagnostic streams — used to investigate whether ConsoleState or
        // WorkoutState transitions correlate with hardware button presses. Gated
        // behind detailed tracing so production sessions don't hold extra server
        // streams open. Remove the gate once we confirm no side effects.
        if (NordicFtmsPreferences.isDetailedTracingEnabled(appContext)) {
            subscribeConsoleState(generation);
            subscribeWorkoutState(generation);
        }
    }

    private synchronized void startEquipmentSpecificSubscriptionsIfNeeded() {
        if (!connected || advancedSubscriptionsStarted) {
            return;
        }

        if (isBikeDevice() || isElliptical()) {
            advancedSubscriptionsStarted = true;
            long generation = activeGeneration;
            logger.info(appContext, "grpc_start_advanced_subscriptions", "Starting resistance/cadence/watts subscriptions for " + machineType);
            subscribeResistance(generation);
            subscribeCadence(generation);
            subscribeWatts(generation);
        }
    }

    private void subscribeConsoleState(long generation) {
        if (!connected || consoleAsyncStub == null) {
            return;
        }

        consoleAsyncStub.consoleStateChanged(Empty.getDefaultInstance(), new StreamObserver<ConsoleStateResponse>() {
            @Override
            public void onNext(ConsoleStateResponse data) {
                logger.trace(appContext, "console_state_changed",
                        "consoleState=" + data.getConsoleState());
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "console_state_subscription",
                        "console_state_subscription_error",
                        "Console state subscription error",
                        t,
                        generation,
                        () -> subscribeConsoleState(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "console_state_subscription_completed", "Console state subscription completed");
            }
        });
    }

    private void subscribeWorkoutState(long generation) {
        if (!connected || workoutAsyncStub == null) {
            return;
        }

        workoutAsyncStub.workoutStateChanged(Empty.getDefaultInstance(), new StreamObserver<WorkoutStateResponse>() {
            @Override
            public void onNext(WorkoutStateResponse data) {
                logger.trace(appContext, "workout_state_changed",
                        "workoutState=" + data.getWorkoutState());
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "workout_state_subscription",
                        "workout_state_subscription_error",
                        "Workout state subscription error",
                        t,
                        generation,
                        () -> subscribeWorkoutState(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "workout_state_subscription_completed", "Workout state subscription completed");
            }
        });
    }

    private void subscribeSpeed(long generation) {
        if (!connected || speedAsyncStub == null) {
            return;
        }

        speedAsyncStub.speedSubscription(Empty.getDefaultInstance(), new StreamObserver<SpeedData>() {
            @Override
            public void onNext(SpeedData data) {
                lastSpeedKph = data.getLastKph();
                cachedSpeedKph = lastSpeedKph;
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "speed_subscription",
                        "speed_subscription_error",
                        "Speed subscription error",
                        t,
                        generation,
                        () -> subscribeSpeed(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "speed_subscription_completed", "Speed subscription completed");
            }
        });
    }

    private void subscribeIncline(long generation) {
        if (!connected || inclineAsyncStub == null) {
            return;
        }

        inclineAsyncStub.inclineSubscription(Empty.getDefaultInstance(), new StreamObserver<InclineData>() {
            private double priorValue = Double.NaN;

            @Override
            public void onNext(InclineData data) {
                double value = data.getLastInclinePercent();
                double delta = Double.isNaN(priorValue) ? 0.0 : value - priorValue;
                priorValue = value;
                lastInclinePercent = value;
                cachedInclinePercent = value;
                logger.trace(appContext, "incline_observation",
                        String.format(java.util.Locale.US,
                                "value=%.3f%% delta=%+.3f%% workoutId=%s timeSeconds=%d",
                                value, delta, data.getWorkoutID(), data.getTimeSeconds()));
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "incline_subscription",
                        "incline_subscription_error",
                        "Incline subscription error",
                        t,
                        generation,
                        () -> subscribeIncline(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "incline_subscription_completed", "Incline subscription completed");
            }
        });
    }

    private void subscribeDistance(long generation) {
        if (!connected || distanceAsyncStub == null) {
            return;
        }

        distanceAsyncStub.distanceSubscription(Empty.getDefaultInstance(), new StreamObserver<DistanceData>() {
            @Override
            public void onNext(DistanceData data) {
                lastDistanceKm = data.getLastDistanceKm();
                cachedDistanceKm = lastDistanceKm;
                logger.debug(appContext, "distance_subscription_update",
                        "Distance: " + lastDistanceKm + " km (" + (int) (lastDistanceKm * 1000) + " m)");
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "distance_subscription",
                        "distance_subscription_error",
                        "Distance subscription error",
                        t,
                        generation,
                        () -> subscribeDistance(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "distance_subscription_completed", "Distance subscription completed");
            }
        });
    }

    private void subscribeResistance(long generation) {
        if (!connected || resistanceAsyncStub == null) {
            return;
        }

        resistanceAsyncStub.resistanceSubscription(Empty.getDefaultInstance(), new StreamObserver<ResistanceData>() {
            @Override
            public void onNext(ResistanceData data) {
                lastResistance = data.getLastResistance();
                cachedResistance = lastResistance;
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "resistance_subscription",
                        "resistance_subscription_error",
                        "Resistance subscription error",
                        t,
                        generation,
                        () -> subscribeResistance(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "resistance_subscription_completed", "Resistance subscription completed");
            }
        });
    }

    private void subscribeCadence(long generation) {
        if (!connected || cadenceAsyncStub == null) {
            return;
        }

        cadenceAsyncStub.cadenceSubscription(Empty.getDefaultInstance(), new StreamObserver<CadenceData>() {
            @Override
            public void onNext(CadenceData data) {
                lastCadenceRpm = data.getLastRpm();
                cachedCadenceRpm = lastCadenceRpm;
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "cadence_subscription",
                        "cadence_subscription_error",
                        "Cadence subscription error",
                        t,
                        generation,
                        () -> subscribeCadence(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "cadence_subscription_completed", "Cadence subscription completed");
            }
        });
    }

    private void subscribeWatts(long generation) {
        if (!connected || wattsAsyncStub == null) {
            return;
        }

        wattsAsyncStub.wattsSubscription(Empty.getDefaultInstance(), new StreamObserver<WattsData>() {
            @Override
            public void onNext(WattsData data) {
                lastWatts = data.getLastWatts();
                cachedWatts = lastWatts;
            }

            @Override
            public void onError(Throwable t) {
                handleSubscriptionError(
                        "watts_subscription",
                        "watts_subscription_error",
                        "Watts subscription error",
                        t,
                        generation,
                        () -> subscribeWatts(generation),
                        3000
                );
            }

            @Override
            public void onCompleted() {
                logger.info(appContext, "watts_subscription_completed", "Watts subscription completed");
            }
        });
    }

    private void retrySubscription(Runnable subscription, long delayMs, long generation) {
        if (!connected || generation != activeGeneration) {
            return;
        }

        callbackExecutor.schedule(() -> {
            if (connected && generation == activeGeneration) {
                subscription.run();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void retryConsoleInfoSubscription(long delayMs, long generation) {
        if (channel == null || generation != activeGeneration) {
            return;
        }

        callbackExecutor.schedule(() -> {
            if (channel != null && generation == activeGeneration) {
                subscribeConsoleInfo(generation);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void handleCommandFailure(
            String source,
            String eventName,
            String message,
            StatusRuntimeException error,
            long generation
    ) {
        if (handleGrpcFailure(source, error, generation)) {
            return;
        }
        logger.warn(appContext, eventName, message + ": " + summarizeTransportError(error), error);
    }

    private void handleSubscriptionError(
            String source,
            String eventName,
            String message,
            Throwable error,
            long generation,
            Runnable retryAction,
            long retryDelayMs
    ) {
        if (handleGrpcFailure(source, error, generation)) {
            return;
        }
        logger.warn(appContext, eventName, message + ": " + summarizeTransportError(error), error);
        if (isNonTransientError(error)) {
            logger.error(appContext, eventName + "_permanent",
                    "Not retrying " + source + " — error is non-transient", error);
            return;
        }
        retrySubscription(retryAction, retryDelayMs, generation);
    }

    private static boolean isNonTransientError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof StatusRuntimeException) {
                Status.Code code = ((StatusRuntimeException) t).getStatus().getCode();
                if (code == Status.Code.PERMISSION_DENIED
                        || code == Status.Code.UNAUTHENTICATED
                        || code == Status.Code.UNIMPLEMENTED
                        || code == Status.Code.INVALID_ARGUMENT) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- Getters for latest values ---

    public double getLastSpeedKph() { return connected ? lastSpeedKph : cachedSpeedKph; }

    public double getLastInclinePercent() { return connected ? lastInclinePercent : cachedInclinePercent; }

    public double getLastDistanceKm() { return connected ? lastDistanceKm : cachedDistanceKm; }

    public double getLastResistance() { return connected ? lastResistance : cachedResistance; }

    public double getLastCadenceRpm() { return connected ? lastCadenceRpm : cachedCadenceRpm; }

    public double getLastWatts() { return connected ? lastWatts : cachedWatts; }

    // --- Supported Ranges (from ConsoleInfo) ---

    public double getMinSpeedKph() {
        ConsoleInfo info = getConsoleInfo();
        return info != null ? info.getMinKph() : 0.5;
    }

    public double getMaxSpeedKph() {
        ConsoleInfo info = getConsoleInfo();
        return info != null ? info.getMaxKph() : 22.0;
    }

    public double getMinInclinePercent() {
        ConsoleInfo info = getConsoleInfo();
        return info != null ? info.getMinInclinePercent() : -6.0;
    }

    public double getMaxInclinePercent() {
        ConsoleInfo info = getConsoleInfo();
        return info != null ? info.getMaxInclinePercent() : 40.0;
    }

    public double getMinResistance() {
        ConsoleInfo info = getConsoleInfo();
        return info != null ? info.getMinResistance() : 0;
    }

    public double getMaxResistance() {
        ConsoleInfo info = getConsoleInfo();
        return info != null ? info.getMaxResistance() : 30;
    }

    private SSLSocketFactory createSslSocketFactory() throws Exception {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

        InputStream caInput = appContext.getAssets().open("certs/glassos_ca.pem");
        X509Certificate caCert = (X509Certificate) certFactory.generateCertificate(caInput);
        caInput.close();

        InputStream certInput = appContext.getAssets().open("certs/glassos_client_cert.pem");
        X509Certificate clientCert = (X509Certificate) certFactory.generateCertificate(certInput);
        certInput.close();

        InputStream keyInput = appContext.getAssets().open("certs/glassos_client_key.pem");
        byte[] keyBytes = readAllBytes(keyInput);
        keyInput.close();
        PrivateKey clientKey = parsePrivateKey(keyBytes);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("glassos-ca", caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", clientKey, "".toCharArray(), new Certificate[]{clientCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "".toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        logger.info(appContext, "grpc_ssl_ready", "SSL context created with mTLS (client CN: " + clientCert.getSubjectDN() + ")");
        return sslContext.getSocketFactory();
    }

    private PrivateKey parsePrivateKey(byte[] pemBytes) throws Exception {
        String pem = new String(pemBytes, "UTF-8");
        pem = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = android.util.Base64.decode(pem, android.util.Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        return buffer.toByteArray();
    }

    static boolean shouldSuppressChannelShutdownNoise(
            boolean localDisconnectInProgress,
            boolean generationMatches,
            Throwable error
    ) {
        return !generationMatches || (localDisconnectInProgress && isChannelShutdownSignal(error));
    }

    private synchronized boolean handleGrpcFailure(String source, Throwable error, long generation) {
        if (shouldSuppressChannelShutdownNoise(disconnectReason != DisconnectReason.NONE, generation == activeGeneration, error)) {
            logger.debug(appContext, "grpc_shutdown_noise", "Ignoring callback from a local or stale channel shutdown for " + source);
            return true;
        }
        if (!isBackendUnavailableSignal(error)) {
            return false;
        }

        lastConnectionError = error;
        lastConnectionErrorSource = source;

        if (!connected) {
            return true;
        }

        boolean shouldNotify = backendUnavailableReported.compareAndSet(false, true);
        logger.warn(appContext, "grpc_backend_unavailable", "GlassOS backend became unavailable via " + source, error);
        disconnectInternal(false, DisconnectReason.BACKEND_FAILURE);

        if (shouldNotify && backendUnavailableListener != null) {
            backendUnavailableListener.onGrpcBackendUnavailable(source, error);
        }
        return true;
    }

    static boolean isBackendUnavailableSignal(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof StatusRuntimeException) {
                StatusRuntimeException statusError = (StatusRuntimeException) current;
                Status.Code statusCode = statusError.getStatus().getCode();
                String description = statusError.getStatus().getDescription();
                if (statusCode == Status.Code.UNAVAILABLE || statusCode == Status.Code.DEADLINE_EXCEEDED) {
                    return true;
                }
                if (statusCode == Status.Code.RESOURCE_EXHAUSTED && isKeepAliveThrottleMessage(description)) {
                    return true;
                }
            }

            if (current instanceof ConnectException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && (message.contains("ECONNREFUSED")
                    || message.contains("Connection refused")
                    || message.contains("failed to connect to localhost")
                    || message.contains("UNAVAILABLE")
                    || isKeepAliveThrottleMessage(message))) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private static boolean isKeepAliveThrottleMessage(String message) {
        return message != null && (message.contains("too_many_pings")
                || message.contains("ENHANCE_YOUR_CALM")
                || message.contains("Bandwidth exhausted"));
    }

    private static String summarizeTransportError(Throwable error) {
        if (error instanceof StatusRuntimeException) {
            Status status = ((StatusRuntimeException) error).getStatus();
            if (status.getDescription() == null || status.getDescription().isEmpty()) {
                return status.getCode().name();
            }
            return status.getCode().name() + ": " + status.getDescription();
        }

        String message = error != null ? error.getMessage() : null;
        if (message != null && !message.isEmpty()) {
            return message;
        }
        return error != null ? error.getClass().getSimpleName() : "unknown error";
    }

    private static boolean isChannelShutdownSignal(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof StatusRuntimeException) {
                String message = ((StatusRuntimeException) current).getStatus().getDescription();
                if (message != null && (message.contains("Channel shutdownNow invoked")
                        || message.contains("Channel shutdown invoked")
                        || message.contains("End of stream"))) {
                    return true;
                }
            }

            String message = current.getMessage();
            if (message != null && (message.contains("Channel shutdownNow invoked")
                    || message.contains("Channel shutdown invoked")
                    || message.contains("End of stream or IOException"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void resetConnectionErrors() {
        lastConsoleInfoFetchError = null;
        lastConsoleInfoFetchErrorSource = "unknown";
        lastConnectionError = null;
        lastConnectionErrorSource = "unknown";
        backendUnavailableReported.set(false);
        disconnectReason = DisconnectReason.NONE;
    }

    private void disconnectInternal(boolean log, DisconnectReason reason) {
        // Invalidate the generation first so in-flight stream callbacks bail out
        // before we null the stubs they reference.
        generationCounter.incrementAndGet();

        ManagedChannel channelToClose = channel;
        disconnectReason = reason;

        channel = null;
        speedStub = null;
        inclineStub = null;
        resistanceStub = null;
        workoutStub = null;
        consoleStub = null;
        speedAsyncStub = null;
        inclineAsyncStub = null;
        distanceAsyncStub = null;
        resistanceAsyncStub = null;
        cadenceAsyncStub = null;
        wattsAsyncStub = null;
        consoleAsyncStub = null;
        workoutAsyncStub = null;

        connected = false;
        advancedSubscriptionsStarted = false;
        consoleInfoSubscriptionStarted = false;
        consoleInfo = null;
        machineType = ConsoleType.CONSOLE_TYPE_UNKNOWN;
        lastSpeedKph = 0;
        lastInclinePercent = 0;
        lastDistanceKm = 0;
        lastResistance = 0;
        lastCadenceRpm = 0;
        lastWatts = 0;

        if (channelToClose != null) {
            try {
                channelToClose.shutdown();
                if (!channelToClose.awaitTermination(1500, TimeUnit.MILLISECONDS)) {
                    channelToClose.shutdownNow();
                    channelToClose.awaitTermination(3500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error(appContext, "grpc_shutdown_error", "Error shutting down the gRPC channel", e);
            }
        }

        if (log) {
            logger.info(appContext, "grpc_disconnected", "gRPC channel disconnected");
        }
    }
}
