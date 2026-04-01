package com.nordicftms.app;

import android.content.Context;
import android.util.Log;

import com.ifit.glassos.CadenceData;
import com.ifit.glassos.CadenceServiceGrpc;
import com.ifit.glassos.ConsoleInfo;
import com.ifit.glassos.ConsoleServiceGrpc;
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

import java.io.InputStream;
import java.net.ConnectException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile boolean connected = false;
    private volatile boolean advancedSubscriptionsStarted = false;
    private volatile boolean consoleInfoSubscriptionStarted = false;
    private volatile Throwable lastConsoleInfoFetchError;
    private volatile String lastConsoleInfoFetchErrorSource = "unknown";
    private volatile Throwable lastConnectionError;
    private volatile String lastConnectionErrorSource = "unknown";

    public GrpcControlService(Context context, BackendUnavailableListener listener) {
        this.appContext = context.getApplicationContext();
        this.backendUnavailableListener = listener;
    }

    public synchronized boolean connect() {
        resetConnectionErrors();
        disconnectInternal(false);

        try {
            SSLSocketFactory sslSocketFactory = createSslSocketFactory();

            Metadata headers = new Metadata();
            headers.put(CLIENT_ID_KEY, CLIENT_ID);

            channel = OkHttpChannelBuilder.forAddress(HOST, PORT)
                    .sslSocketFactory(sslSocketFactory)
                    .overrideAuthority("localhost")
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
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
            subscribeConsoleInfo();

            if (!resolveInitialConsoleInfo()) {
                lastConnectionError = lastConsoleInfoFetchError != null
                        ? lastConsoleInfoFetchError
                        : new IllegalStateException("Initial console info fetch did not return usable data");
                lastConnectionErrorSource = lastConsoleInfoFetchErrorSource;
                Log.w(LOG_TAG, "gRPC transport created but backend is not ready yet via "
                        + lastConnectionErrorSource);
                disconnectInternal(false);
                return false;
            }

            connected = true;
            backendUnavailableReported.set(false);
            Log.i(LOG_TAG, "gRPC backend ready on " + HOST + ":" + PORT + " with mTLS");
            return true;

        } catch (Exception e) {
            lastConnectionError = e;
            lastConnectionErrorSource = "connect";
            Log.e(LOG_TAG, "Failed to connect gRPC backend", e);
            disconnectInternal(false);
            return false;
        }
    }

    public synchronized void disconnect() {
        disconnectInternal(true);
        backendUnavailableReported.set(false);
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

    private boolean resolveInitialConsoleInfo() {
        lastConsoleInfoFetchError = null;
        lastConsoleInfoFetchErrorSource = "initial_console_info";

        for (int attempt = 1; attempt <= CONSOLE_INFO_FETCH_ATTEMPTS; attempt++) {
            if (hasUsableConsoleInfo()) {
                return true;
            }

            if (fetchConsoleInfoFromRpc(false, attempt)) {
                return true;
            }
            if (fetchConsoleInfoFromRpc(true, attempt)) {
                return true;
            }

            if (hasUsableConsoleInfo()) {
                return true;
            }

            if (attempt < CONSOLE_INFO_FETCH_ATTEMPTS) {
                Log.w(LOG_TAG, "Console info not ready after attempt " + attempt
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

    private boolean fetchConsoleInfoFromRpc(boolean useKnownConsoleInfo, int attempt) {
        String source = useKnownConsoleInfo ? "GetKnownConsoleInfo" : "GetConsole";

        try {
            ConsoleInfo fetchedConsoleInfo = useKnownConsoleInfo
                    ? consoleStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getKnownConsoleInfo(Empty.getDefaultInstance())
                    : consoleStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getConsole(Empty.getDefaultInstance());

            return applyConsoleInfo(source, fetchedConsoleInfo, attempt);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to fetch console info via " + source
                    + " (attempt " + attempt + ")", e);
            lastConsoleInfoFetchError = e;
            lastConsoleInfoFetchErrorSource = source;
            return false;
        }
    }

    private void subscribeConsoleInfo() {
        if (consoleAsyncStub == null || channel == null || consoleInfoSubscriptionStarted) {
            return;
        }

        consoleInfoSubscriptionStarted = true;
        consoleAsyncStub.consoleChanged(Empty.getDefaultInstance(), new StreamObserver<ConsoleInfo>() {
            @Override
            public void onNext(ConsoleInfo updatedConsoleInfo) {
                applyConsoleInfo("ConsoleChanged", updatedConsoleInfo, 0);
            }

            @Override
            public void onError(Throwable t) {
                consoleInfoSubscriptionStarted = false;
                Log.e(LOG_TAG, "Console info subscription error", t);
                boolean backendUnavailable = handleGrpcFailure("console_subscription", t);
                if (!backendUnavailable) {
                    retrySubscription(GrpcControlService.this::subscribeConsoleInfo, CONSOLE_INFO_STREAM_RETRY_MS);
                } else if (!connected && channel != null) {
                    retryConsoleInfoSubscription(CONSOLE_INFO_STREAM_RETRY_MS);
                }
            }

            @Override
            public void onCompleted() {
                consoleInfoSubscriptionStarted = false;
                Log.i(LOG_TAG, "Console info subscription completed");
            }
        });
    }

    private boolean applyConsoleInfo(String source, ConsoleInfo updatedConsoleInfo, int attempt) {
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
                Log.w(LOG_TAG, "Ignoring empty console info from " + source
                        + " because populated console info is already cached");
                return false;
            }

            ConsoleType previousMachineType = machineType;
            consoleInfo = updatedConsoleInfo;
            machineType = updatedConsoleInfo.getMachineType();
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

        Log.i(LOG_TAG, "Console info received via " + source + attemptSuffix
                + " usable=" + infoUsable);
        Log.i(LOG_TAG, "  Machine type: " + updatedConsoleInfo.getMachineType());
        Log.i(LOG_TAG, "  Name: " + updatedConsoleInfo.getName());
        Log.i(LOG_TAG, "  Speed range: " + updatedConsoleInfo.getMinKph()
                + " - " + updatedConsoleInfo.getMaxKph() + " kph");
        Log.i(LOG_TAG, "  Incline range: " + updatedConsoleInfo.getMinInclinePercent()
                + " - " + updatedConsoleInfo.getMaxInclinePercent() + "%");
        Log.i(LOG_TAG, "  Can set speed: " + updatedConsoleInfo.getCanSetSpeed());
        Log.i(LOG_TAG, "  Can set incline: " + updatedConsoleInfo.getCanSetIncline());
        Log.i(LOG_TAG, "  Can set resistance: " + updatedConsoleInfo.getCanSetResistance());
        Log.i(LOG_TAG, "  Firmware: " + updatedConsoleInfo.getFirmwareVersion());
        Log.i(LOG_TAG, "  Serial: " + updatedConsoleInfo.getProductSerialNumber());
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
        return consoleInfo;
    }

    public ConsoleType getMachineType() {
        return machineType;
    }

    public boolean isBikeDevice() {
        return machineType == ConsoleType.BIKE
                || machineType == ConsoleType.SPIN_BIKE;
    }

    public boolean isTreadmillDevice() {
        return machineType == ConsoleType.TREADMILL
                || machineType == ConsoleType.INCLINE_TRAINER
                || (machineType == ConsoleType.CONSOLE_TYPE_UNKNOWN
                && isTreadmillLikeConsole(consoleInfo));
    }

    public boolean isRower() {
        return machineType == ConsoleType.ROWER;
    }

    public boolean isElliptical() {
        return machineType == ConsoleType.ELLIPTICAL
                || machineType == ConsoleType.VERTICAL_ELLIPTICAL
                || machineType == ConsoleType.STRIDER
                || machineType == ConsoleType.FREE_STRIDER;
    }

    // --- Control Commands ---

    public void setSpeed(double kph) {
        if (!connected) {
            return;
        }

        new Thread(() -> {
            try {
                SetSpeedRequest req = SetSpeedRequest.newBuilder().setKph(kph).build();
                Result result = speedStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setSpeed(req);
                Log.i(LOG_TAG, "SetSpeed(" + kph + " kph) -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                Log.e(LOG_TAG, "SetSpeed failed: " + e.getStatus(), e);
                handleGrpcFailure("set_speed", e);
            } catch (Exception e) {
                Log.e(LOG_TAG, "SetSpeed error", e);
            }
        }).start();
    }

    public void setIncline(double percent) {
        if (!connected) {
            return;
        }

        new Thread(() -> {
            try {
                SetInclineRequest req = SetInclineRequest.newBuilder().setPercent(percent).build();
                Result result = inclineStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setIncline(req);
                Log.i(LOG_TAG, "SetIncline(" + percent + "%) -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                Log.e(LOG_TAG, "SetIncline failed: " + e.getStatus(), e);
                handleGrpcFailure("set_incline", e);
            } catch (Exception e) {
                Log.e(LOG_TAG, "SetIncline error", e);
            }
        }).start();
    }

    public void setResistance(double resistance) {
        if (!connected) {
            return;
        }

        new Thread(() -> {
            try {
                SetResistanceRequest req = SetResistanceRequest.newBuilder().setResistance(resistance).build();
                Result result = resistanceStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setResistance(req);
                Log.i(LOG_TAG, "SetResistance(" + resistance + ") -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                Log.e(LOG_TAG, "SetResistance failed: " + e.getStatus(), e);
                handleGrpcFailure("set_resistance", e);
            } catch (Exception e) {
                Log.e(LOG_TAG, "SetResistance error", e);
            }
        }).start();
    }

    // --- Data Subscriptions ---

    public void startSubscriptions() {
        if (!connected) {
            return;
        }

        Log.i(LOG_TAG, "Starting gRPC data subscriptions");
        subscribeConsoleInfo();
        subscribeSpeed();
        subscribeIncline();
        subscribeDistance();
        startEquipmentSpecificSubscriptionsIfNeeded();
    }

    private synchronized void startEquipmentSpecificSubscriptionsIfNeeded() {
        if (!connected || advancedSubscriptionsStarted) {
            return;
        }

        if (isBikeDevice() || isElliptical()) {
            advancedSubscriptionsStarted = true;
            Log.i(LOG_TAG, "Starting resistance/cadence/watts subscriptions for " + machineType);
            subscribeResistance();
            subscribeCadence();
            subscribeWatts();
        }
    }

    private void subscribeSpeed() {
        if (!connected || speedAsyncStub == null) {
            return;
        }

        speedAsyncStub.speedSubscription(Empty.getDefaultInstance(), new StreamObserver<SpeedData>() {
            @Override
            public void onNext(SpeedData data) {
                lastSpeedKph = data.getLastKph();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Speed subscription error", t);
                if (!handleGrpcFailure("speed_subscription", t)) {
                    retrySubscription(GrpcControlService.this::subscribeSpeed, 3000);
                }
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Speed subscription completed");
            }
        });
    }

    private void subscribeIncline() {
        if (!connected || inclineAsyncStub == null) {
            return;
        }

        inclineAsyncStub.inclineSubscription(Empty.getDefaultInstance(), new StreamObserver<InclineData>() {
            @Override
            public void onNext(InclineData data) {
                lastInclinePercent = data.getLastInclinePercent();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Incline subscription error", t);
                if (!handleGrpcFailure("incline_subscription", t)) {
                    retrySubscription(GrpcControlService.this::subscribeIncline, 3000);
                }
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Incline subscription completed");
            }
        });
    }

    private void subscribeDistance() {
        if (!connected || distanceAsyncStub == null) {
            return;
        }

        distanceAsyncStub.distanceSubscription(Empty.getDefaultInstance(), new StreamObserver<DistanceData>() {
            @Override
            public void onNext(DistanceData data) {
                lastDistanceKm = data.getLastDistanceKm();
                Log.d(LOG_TAG, "Distance: " + lastDistanceKm + " km (" + (int) (lastDistanceKm * 1000) + " m)");
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Distance subscription error", t);
                if (!handleGrpcFailure("distance_subscription", t)) {
                    retrySubscription(GrpcControlService.this::subscribeDistance, 3000);
                }
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Distance subscription completed");
            }
        });
    }

    private void subscribeResistance() {
        if (!connected || resistanceAsyncStub == null) {
            return;
        }

        resistanceAsyncStub.resistanceSubscription(Empty.getDefaultInstance(), new StreamObserver<ResistanceData>() {
            @Override
            public void onNext(ResistanceData data) {
                lastResistance = data.getLastResistance();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Resistance subscription error", t);
                if (!handleGrpcFailure("resistance_subscription", t)) {
                    retrySubscription(GrpcControlService.this::subscribeResistance, 3000);
                }
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Resistance subscription completed");
            }
        });
    }

    private void subscribeCadence() {
        if (!connected || cadenceAsyncStub == null) {
            return;
        }

        cadenceAsyncStub.cadenceSubscription(Empty.getDefaultInstance(), new StreamObserver<CadenceData>() {
            @Override
            public void onNext(CadenceData data) {
                lastCadenceRpm = data.getLastRpm();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Cadence subscription error", t);
                if (!handleGrpcFailure("cadence_subscription", t)) {
                    retrySubscription(GrpcControlService.this::subscribeCadence, 3000);
                }
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Cadence subscription completed");
            }
        });
    }

    private void subscribeWatts() {
        if (!connected || wattsAsyncStub == null) {
            return;
        }

        wattsAsyncStub.wattsSubscription(Empty.getDefaultInstance(), new StreamObserver<WattsData>() {
            @Override
            public void onNext(WattsData data) {
                lastWatts = data.getLastWatts();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Watts subscription error", t);
                if (!handleGrpcFailure("watts_subscription", t)) {
                    retrySubscription(GrpcControlService.this::subscribeWatts, 3000);
                }
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Watts subscription completed");
            }
        });
    }

    private void retrySubscription(Runnable subscription, long delayMs) {
        if (!connected) {
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (connected) {
                    subscription.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void retryConsoleInfoSubscription(long delayMs) {
        if (channel == null) {
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (channel != null) {
                    subscribeConsoleInfo();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    // --- Getters for latest values ---

    public double getLastSpeedKph() { return lastSpeedKph; }

    public double getLastInclinePercent() { return lastInclinePercent; }

    public double getLastDistanceKm() { return lastDistanceKm; }

    public double getLastResistance() { return lastResistance; }

    public double getLastCadenceRpm() { return lastCadenceRpm; }

    public double getLastWatts() { return lastWatts; }

    // --- Supported Ranges (from ConsoleInfo) ---

    public double getMinSpeedKph() {
        return consoleInfo != null ? consoleInfo.getMinKph() : 0.5;
    }

    public double getMaxSpeedKph() {
        return consoleInfo != null ? consoleInfo.getMaxKph() : 22.0;
    }

    public double getMinInclinePercent() {
        return consoleInfo != null ? consoleInfo.getMinInclinePercent() : -6.0;
    }

    public double getMaxInclinePercent() {
        return consoleInfo != null ? consoleInfo.getMaxInclinePercent() : 40.0;
    }

    public double getMinResistance() {
        return consoleInfo != null ? consoleInfo.getMinResistance() : 0;
    }

    public double getMaxResistance() {
        return consoleInfo != null ? consoleInfo.getMaxResistance() : 30;
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

        Log.i(LOG_TAG, "SSL context created with mTLS (client CN: " + clientCert.getSubjectDN() + ")");
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

    private synchronized boolean handleGrpcFailure(String source, Throwable error) {
        if (!isBackendUnavailable(error)) {
            return false;
        }

        lastConnectionError = error;
        lastConnectionErrorSource = source;

        if (!connected) {
            return true;
        }

        boolean shouldNotify = backendUnavailableReported.compareAndSet(false, true);
        Log.w(LOG_TAG, "GlassOS backend became unavailable via " + source, error);
        disconnectInternal(false);

        if (shouldNotify && backendUnavailableListener != null) {
            backendUnavailableListener.onGrpcBackendUnavailable(source, error);
        }
        return true;
    }

    private boolean isBackendUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof StatusRuntimeException) {
                Status.Code statusCode = ((StatusRuntimeException) current).getStatus().getCode();
                if (statusCode == Status.Code.UNAVAILABLE || statusCode == Status.Code.DEADLINE_EXCEEDED) {
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
                    || message.contains("UNAVAILABLE"))) {
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
    }

    private void disconnectInternal(boolean log) {
        ManagedChannel channelToClose = channel;

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
                channelToClose.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(LOG_TAG, "Error shutting down gRPC channel", e);
            }
        }

        if (log) {
            Log.i(LOG_TAG, "gRPC channel disconnected");
        }
    }
}
