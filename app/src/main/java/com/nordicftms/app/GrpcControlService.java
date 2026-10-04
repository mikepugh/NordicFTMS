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
import java.util.concurrent.RejectedExecutionException;
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
 * gRPC client that tries localhost:54321, then verified loopback listeners with mTLS.
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
    private volatile ConsoleDiscoveryDiagnostics consoleDiscovery = new ConsoleDiscoveryDiagnostics();
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
    private final BackendAttemptTrace backendAttempts = new BackendAttemptTrace();
    private final LoopbackBackendDiscovery endpointDiscovery = new LoopbackBackendDiscovery();
    private final java.util.List<java.util.Map<String, Object>> endpointHistory = new java.util.ArrayList<>();
    private LoopbackBackendDiscovery.Endpoint lastWorkingEndpoint;
    private volatile String activeEndpoint = HOST + ":" + PORT;
    private volatile String activeConnectionPath = "grpc_default";
    private volatile java.util.Map<String, Object> tlsDetails = java.util.Collections.emptyMap();
    private volatile java.util.Map<String, Object> discoveryDetails = java.util.Collections.emptyMap();
    private java.util.Map<String, Object> primaryFailure = java.util.Collections.emptyMap();

    public GrpcControlService(Context context, BackendUnavailableListener listener) {
        this.appContext = context.getApplicationContext();
        this.backendUnavailableListener = listener;
    }

    public synchronized boolean connect() {
        primaryFailure = java.util.Collections.emptyMap();
        discoveryDetails = java.util.Collections.emptyMap();
        updateBackendStatus("Checking GlassOS", HOST + ":" + PORT);
        if (tryEndpoint(HOST, PORT, "grpc_default", CONSOLE_INFO_FETCH_ATTEMPTS, 5000)) return true;
        primaryFailure = endpointHistory.get(endpointHistory.size() - 1);
        Throwable primaryError = lastConnectionError;
        if (Thread.currentThread().isInterrupted()) return false;
        if (lastWorkingEndpoint != null) {
            endpointDiscovery.validationAttempted(lastWorkingEndpoint);
            if (tryEndpoint(lastWorkingEndpoint.host, lastWorkingEndpoint.port, "grpc_remembered", 1, 1500)) return true;
        }
        if (Thread.currentThread().isInterrupted()) return false;
        updateBackendStatus("Discovering GlassOS", "Checking local TCP listeners");
        LoopbackBackendDiscovery.Result discovery = endpointDiscovery.discover();
        discoveryDetails = discovery.details;
        backendAttempts.record("loopback_discovery", "tcp_listeners", String.valueOf(discoveryDetails.get("scan_outcome")),
                ((Number) discoveryDetails.get("duration_ms")).longValue(), discoveryDetails.toString());
        recordConnectionDiagnostics();
        int attempted = 0;
        for (LoopbackBackendDiscovery.Endpoint candidate : discovery.candidates) {
            if (Thread.currentThread().isInterrupted() || attempted >= 4) break;
            endpointDiscovery.validationAttempted(candidate);
            attempted++;
            java.util.Map<String, Object> progress = new java.util.LinkedHashMap<>(discovery.details);
            progress.put("validation_attempts", attempted);
            progress.put("validation_deferred", discovery.candidates.size() - attempted);
            discoveryDetails = java.util.Collections.unmodifiableMap(progress);
            if (tryEndpoint(candidate.host, candidate.port, "grpc_discovered", 1, 1500)) {
                lastWorkingEndpoint = candidate;
                SupportDiagnostics.request(appContext, "alternate_endpoint_selected", false);
                return true;
            }
        }
        updateBackendStatus("No verified GlassOS endpoint", "Default: " + primaryFailure.get("failure_category")
                + "; discovery: " + discoveryDetails.get("scan_outcome"));
        lastConnectionError = new IllegalStateException("No verified GlassOS endpoint. Default " + HOST + ":" + PORT
                + ": " + primaryFailure.get("error") + "; discovery: " + discoveryDetails.get("scan_outcome"), primaryError);
        lastConnectionErrorSource = "backend_discovery";
        recordConnectionDiagnostics();
        logger.warn(appContext, "backend_paths_unavailable", "No verified GlassOS endpoint. Default: "
                + primaryFailure.get("error") + "; discovery: " + discoveryDetails.get("scan_outcome"));
        return false;
    }

    private boolean tryEndpoint(String host, int port, String path, int attempts, long rpcTimeoutMs) {
        activeEndpoint = host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        activeConnectionPath = path;
        long started = System.nanoTime();
        backendAttempts.record(path, "connect", "started", 0, activeEndpoint);
        updateBackendStatus("Checking GlassOS", activeEndpoint);
        boolean ready = connectGrpc(host, port, attempts, rpcTimeoutMs);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        backendAttempts.record(path, "console_discovery", ready ? "ready" : "failed", durationMs,
                activeEndpoint + ": " + consoleDiscovery.describe());
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("endpoint", activeEndpoint);
        summary.put("path", path);
        summary.put("at_ms", System.currentTimeMillis());
        summary.put("duration_ms", durationMs);
        summary.put("ready", ready);
        summary.put("failure_category", ready ? "none" : BackendAttemptTrace.classify(lastConnectionError));
        summary.put("error", lastConnectionError == null ? "" : ConsoleDiscoveryDiagnostics.describeError(lastConnectionError));
        summary.put("rpc_outcomes", consoleDiscovery.describe());
        summary.put("handshake", tlsDetails.get("handshake"));
        summary.put("server_chain_validation", tlsDetails.get("server_chain_validation"));
        Object peer = tlsDetails.get("presented_peer_leaf");
        if (peer instanceof java.util.Map) summary.put("presented_peer_sha256", ((java.util.Map<?, ?>) peer).get("sha256"));
        endpointHistory.add(java.util.Collections.unmodifiableMap(summary));
        if (endpointHistory.size() > 8) endpointHistory.remove(0);
        if (ready) updateBackendStatus("GlassOS gRPC", activeEndpoint + " (verified)");
        recordConnectionDiagnostics();
        return ready;
    }

    private boolean connectGrpc(String host, int port, int attempts, long rpcTimeoutMs) {
        disconnectInternal(false, DisconnectReason.LOCAL_REQUEST);
        resetConnectionErrors();
        tlsDetails = java.util.Collections.emptyMap();
        long generation = generationCounter.incrementAndGet();
        activeGeneration = generation;
        disconnectReason = DisconnectReason.NONE;

        try {
            SSLSocketFactory sslSocketFactory = createSslSocketFactory();

            Metadata headers = new Metadata();
            headers.put(CLIENT_ID_KEY, CLIENT_ID);

            channel = OkHttpChannelBuilder.forAddress(host, port)
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

            if (!resolveInitialConsoleInfo(generation, attempts, rpcTimeoutMs)) {
                lastConnectionError = consoleDiscovery.asException();
                lastConnectionErrorSource = "console_discovery";
                recordConnectionDiagnostics();
                logger.warn(appContext, "grpc_backend_not_ready",
                        "GlassOS console discovery is not ready at " + activeEndpoint + ". "
                                + consoleDiscovery.describe(), lastConnectionError);
                disconnectInternal(false, DisconnectReason.CONNECT_RETRY);
                return false;
            }

            connected = true;
            backendUnavailableReported.set(false);
            disconnectReason = DisconnectReason.NONE;
            recordConnectionDiagnostics();
            logger.info(appContext, "grpc_ready", "gRPC backend ready on " + activeEndpoint + " with mTLS");
            return true;

        } catch (Exception e) {
            lastConnectionError = e;
            lastConnectionErrorSource = "connect";
            backendAttempts.record(activeConnectionPath, "setup", BackendAttemptTrace.classify(e), 0,
                    ConsoleDiscoveryDiagnostics.describeError(e));
            recordConnectionDiagnostics();
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

    private void updateBackendStatus(String name, String summary) {
        NordicFtmsStatusStore.getInstance().update(snapshot -> {
            snapshot.backendPath = name;
            snapshot.backendAttemptSummary = summary;
        });
    }

    public Throwable getLastConnectionError() {
        return lastConnectionError;
    }

    public String getLastConnectionErrorSource() {
        return lastConnectionErrorSource;
    }

    private void recordConnectionDiagnostics() {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("captured_at_ms", System.currentTimeMillis());
        details.put("endpoint", activeEndpoint);
        details.put("connected", connected);
        details.put("generation", activeGeneration);
        details.put("rpc_outcomes", consoleDiscovery.snapshotOutcomes());
        details.put("attempts", backendAttempts.snapshot());
        details.put("tls", tlsDetails);
        details.put("discovery", discoveryDetails);
        details.put("primary_failure", primaryFailure);
        details.put("endpoint_history", new java.util.ArrayList<>(endpointHistory));
        details.put("active_path", connected ? activeConnectionPath : "none");
        details.put("error_source", lastConnectionErrorSource);
        if (lastConnectionError != null) {
            details.put("error", ConsoleDiscoveryDiagnostics.describeError(lastConnectionError));
        }
        ManagedChannel currentChannel = channel;
        try {
            details.put("channel_state", currentChannel == null ? "not_created" : currentChannel.getState(false).name());
        } catch (UnsupportedOperationException error) {
            details.put("channel_state", "unavailable");
        }
        SupportDiagnostics.recordBackend(details);
    }

    // --- Console Info ---

    private boolean resolveInitialConsoleInfo(long generation, int attempts, long rpcTimeoutMs) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (generation != activeGeneration || Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (hasUsableConsoleInfo()) {
                return true;
            }

            if (fetchConsoleInfoFromRpc(false, attempt, generation, rpcTimeoutMs)) {
                return true;
            }
            if (fetchConsoleInfoFromRpc(true, attempt, generation, rpcTimeoutMs)) {
                return true;
            }

            if (hasUsableConsoleInfo()) {
                return true;
            }

            if (attempt < attempts) {
                logger.info(appContext, "console_info_retry", "Console info not ready after attempt " + attempt
                        + ", retrying in " + CONSOLE_INFO_FETCH_RETRY_MS + " ms");
                try {
                    Thread.sleep(CONSOLE_INFO_FETCH_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consoleDiscovery.recordFailure("initial_console_info_resolution", e);
                    return false;
                }
            }
        }

        return hasUsableConsoleInfo();
    }

    private boolean fetchConsoleInfoFromRpc(boolean useKnownConsoleInfo, int attempt, long generation, long rpcTimeoutMs) {
        String source = useKnownConsoleInfo ? "GetKnownConsoleInfo" : "GetConsole";
        if (generation != activeGeneration || Thread.currentThread().isInterrupted() || consoleDiscovery.isUnsupported(source)) {
            return false;
        }

        long started = android.os.SystemClock.elapsedRealtime();
        try {
            ConsoleInfo fetchedConsoleInfo = useKnownConsoleInfo
                    ? consoleStub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS)
                    .getKnownConsoleInfo(Empty.getDefaultInstance())
                    : consoleStub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS)
                    .getConsole(Empty.getDefaultInstance());

            boolean usable = applyConsoleInfo(source, fetchedConsoleInfo, attempt, generation);
            backendAttempts.record(activeConnectionPath, source, usable ? "ready" : "empty_response",
                    android.os.SystemClock.elapsedRealtime() - started, activeEndpoint + "; attempt=" + attempt);
            return usable;
        } catch (Exception e) {
            if (hasUsableConsoleInfo()) {
                logger.info(appContext, "console_info_fetch_ignored", "Console info fetch via " + source
                        + " failed on attempt " + attempt + " but usable console info is already cached");
                return true;
            }
            if (isBackendUnavailableSignal(e) || isChannelShutdownSignal(e)
                    || Status.fromThrowable(e).getCode() == Status.Code.UNIMPLEMENTED) {
                logger.info(appContext, "console_info_fetch_retryable", "Console info via " + source
                        + " is not ready yet on attempt " + attempt + ": " + summarizeTransportError(e));
            } else {
                logger.error(appContext, "console_info_fetch_error", "Failed to fetch console info via " + source
                        + " (attempt " + attempt + ")", e);
            }
            consoleDiscovery.recordFailure(source, e);
            backendAttempts.record(activeConnectionPath, source, BackendAttemptTrace.classify(e),
                    android.os.SystemClock.elapsedRealtime() - started, ConsoleDiscoveryDiagnostics.describeError(e));
            return false;
        }
    }

    private void subscribeConsoleInfo(long generation) {
        ConsoleServiceGrpc.ConsoleServiceStub stub;
        synchronized (consoleInfoLock) {
            if (generation != activeGeneration || consoleAsyncStub == null || channel == null
                    || consoleInfoSubscriptionStarted || consoleDiscovery.isUnsupported("ConsoleChanged")) {
                return;
            }
            consoleInfoSubscriptionStarted = true;
            stub = consoleAsyncStub;
        }

        stub.consoleChanged(Empty.getDefaultInstance(), new StreamObserver<ConsoleInfo>() {
            @Override
            public void onNext(ConsoleInfo updatedConsoleInfo) {
                applyConsoleInfo("ConsoleChanged", updatedConsoleInfo, 0, generation);
            }

            @Override
            public void onError(Throwable t) {
                synchronized (consoleInfoLock) {
                    if (generation != activeGeneration) return;
                    consoleInfoSubscriptionStarted = false;
                    consoleDiscovery.recordFailure("ConsoleChanged", t);
                }
                if (!connected) {
                    // Discovery streams must recover even before readiness is established.
                    if (!isNonTransientError(t)) {
                        retryConsoleInfoSubscription(CONSOLE_INFO_STREAM_RETRY_MS, generation);
                    }
                    return;
                }
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
                synchronized (consoleInfoLock) {
                    if (generation != activeGeneration) return;
                    consoleInfoSubscriptionStarted = false;
                    consoleDiscovery.recordStreamCompleted();
                }
                logger.info(appContext, "console_subscription_completed", "Console info subscription completed; resubscribing");
                retryConsoleInfoSubscription(CONSOLE_INFO_STREAM_RETRY_MS, generation);
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
            if (generation != activeGeneration) return false;
            consoleDiscovery.recordResponse(source, infoUsable);
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
        // streams open. Retry attempts must obey the same diagnostic lifetime.
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
        if (!connected || consoleAsyncStub == null
                || !NordicFtmsPreferences.isDetailedTracingEnabled(appContext)) {
            return;
        }

        consoleAsyncStub.consoleStateChanged(Empty.getDefaultInstance(), new StreamObserver<ConsoleStateResponse>() {
            @Override
            public void onNext(ConsoleStateResponse data) {
                if (generation != activeGeneration) return;
                logger.trace(appContext, "console_state_changed",
                        "consoleState=" + data.getConsoleState());
            }

            @Override
            public void onError(Throwable t) {
                if (!NordicFtmsPreferences.isDetailedTracingEnabled(appContext)) return;
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
        if (!connected || workoutAsyncStub == null
                || !NordicFtmsPreferences.isDetailedTracingEnabled(appContext)) {
            return;
        }

        workoutAsyncStub.workoutStateChanged(Empty.getDefaultInstance(), new StreamObserver<WorkoutStateResponse>() {
            @Override
            public void onNext(WorkoutStateResponse data) {
                if (generation != activeGeneration) return;
                logger.trace(appContext, "workout_state_changed",
                        "workoutState=" + data.getWorkoutState());
            }

            @Override
            public void onError(Throwable t) {
                if (!NordicFtmsPreferences.isDetailedTracingEnabled(appContext)) return;
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
                if (generation != activeGeneration) return;
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
                if (generation != activeGeneration) return;
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
                if (generation != activeGeneration) return;
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
                if (generation != activeGeneration) return;
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
                if (generation != activeGeneration) return;
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
                if (generation != activeGeneration) return;
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

        try {
            callbackExecutor.schedule(() -> {
                if (channel != null && generation == activeGeneration) {
                    subscribeConsoleInfo(generation);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // A stream can finish while shutdown() is retiring the executor.
            if (!callbackExecutor.isShutdown()) throw e;
        }
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

        java.util.Map<String, Object> credentials = new java.util.LinkedHashMap<>();
        credentials.put("ca", TlsDiagnostics.certificate(caCert));
        credentials.put("client", TlsDiagnostics.certificate(clientCert));
        credentials.put("handshake", "not_observed");
        tlsDetails = java.util.Collections.unmodifiableMap(credentials);
        final long generation = activeGeneration;
        final String path = activeConnectionPath;
        final String endpoint = activeEndpoint;
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), TlsDiagnostics.observeTrust(tmf.getTrustManagers(), observed -> {
            synchronized (consoleInfoLock) {
                if (generation != activeGeneration) return;
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>(tlsDetails);
                result.putAll(observed);
                tlsDetails = java.util.Collections.unmodifiableMap(result);
                backendAttempts.record(path, "server_chain_validation", String.valueOf(observed.get("server_chain_validation")),
                        0, endpoint + "; " + String.valueOf(observed.get("validation_error")));
            }
        }), null);
        backendAttempts.record(path, "tls_configuration", "ready", 0,
                endpoint + "; credentials loaded; this does not establish a TLS connection");
        return new TlsDiagnostics(sslContext.getSocketFactory(), observed -> {
            synchronized (consoleInfoLock) {
                if (generation != activeGeneration) return;
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>(tlsDetails);
                result.putAll(observed);
                tlsDetails = java.util.Collections.unmodifiableMap(result);
                backendAttempts.record(path, "tls_handshake", "completed",
                        ((Number) observed.get("duration_ms")).longValue(), endpoint + "; " + observed.get("protocol"));
            }
        });
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
        recordConnectionDiagnostics();
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
        consoleDiscovery = new ConsoleDiscoveryDiagnostics();
        lastConnectionError = null;
        lastConnectionErrorSource = "unknown";
        backendUnavailableReported.set(false);
        disconnectReason = DisconnectReason.NONE;
    }

    private void disconnectInternal(boolean log, DisconnectReason reason) {
        // Invalidate the generation first so in-flight stream callbacks bail out
        // before we null the stubs they reference.
        synchronized (consoleInfoLock) {
            activeGeneration = generationCounter.incrementAndGet();
        }

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
            // All streams are intentionally being discarded. Do not wait for their
            // callbacks while holding the same monitor used by failure handling.
            channelToClose.shutdownNow();
        }

        if (log) {
            logger.info(appContext, "grpc_disconnected", "gRPC channel disconnected");
        }
    }
}
