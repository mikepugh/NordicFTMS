package com.nordicftms.app;

import android.content.Context;
import android.util.Log;

import com.ifit.glassos.*;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * gRPC client that connects to the GlassOS service on localhost:443 with mTLS.
 * Uses certificates extracted from the GlassOS APK to authenticate as com.ifit.dev_app.
 */
public class GrpcControlService {
    private static final String LOG_TAG = "GRPC";
    private static final String HOST = "localhost";
    private static final int PORT = 54321;
    private static final String CLIENT_ID = "com.ifit.dev_app";

    private static final Metadata.Key<String> CLIENT_ID_KEY =
            Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER);

    private ManagedChannel channel;
    private Context appContext;

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

    public GrpcControlService(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void connect() {
        try {
            SSLSocketFactory sslSocketFactory = createSslSocketFactory();

            // Add client_id metadata to all calls
            Metadata headers = new Metadata();
            headers.put(CLIENT_ID_KEY, CLIENT_ID);

            channel = OkHttpChannelBuilder.forAddress(HOST, PORT)
                    .sslSocketFactory(sslSocketFactory)
                    .overrideAuthority("localhost")
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .build();

            // Blocking stubs for commands
            speedStub = SpeedServiceGrpc.newBlockingStub(channel);
            inclineStub = InclineServiceGrpc.newBlockingStub(channel);
            resistanceStub = ResistanceServiceGrpc.newBlockingStub(channel);
            workoutStub = WorkoutServiceGrpc.newBlockingStub(channel);
            consoleStub = ConsoleServiceGrpc.newBlockingStub(channel);

            // Async stubs for subscriptions
            speedAsyncStub = SpeedServiceGrpc.newStub(channel);
            inclineAsyncStub = InclineServiceGrpc.newStub(channel);
            distanceAsyncStub = DistanceServiceGrpc.newStub(channel);
            resistanceAsyncStub = ResistanceServiceGrpc.newStub(channel);
            cadenceAsyncStub = CadenceServiceGrpc.newStub(channel);
            wattsAsyncStub = WattsServiceGrpc.newStub(channel);
            consoleAsyncStub = ConsoleServiceGrpc.newStub(channel);
            workoutAsyncStub = WorkoutServiceGrpc.newStub(channel);

            connected = true;
            Log.i(LOG_TAG, "gRPC channel connected to " + HOST + ":" + PORT + " with mTLS");

            // Fetch console info to learn machine type and capabilities
            fetchConsoleInfo();

        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to connect gRPC channel", e);
            connected = false;
        }
    }

    private SSLSocketFactory createSslSocketFactory() throws Exception {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

        // Load CA certificate (to trust the server)
        InputStream caInput = appContext.getAssets().open("certs/glassos_ca.pem");
        X509Certificate caCert = (X509Certificate) certFactory.generateCertificate(caInput);
        caInput.close();

        // Load client certificate
        InputStream certInput = appContext.getAssets().open("certs/glassos_client_cert.pem");
        X509Certificate clientCert = (X509Certificate) certFactory.generateCertificate(certInput);
        certInput.close();

        // Load client private key
        InputStream keyInput = appContext.getAssets().open("certs/glassos_client_key.pem");
        byte[] keyBytes = readAllBytes(keyInput);
        keyInput.close();
        PrivateKey clientKey = parsePrivateKey(keyBytes);

        // Set up trust manager with our CA
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("glassos-ca", caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Set up key manager with our client cert + key
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", clientKey, "".toCharArray(), new Certificate[]{clientCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "".toCharArray());

        // Create SSL context with mutual TLS
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        Log.i(LOG_TAG, "SSL context created with mTLS (client CN: " + clientCert.getSubjectDN() + ")");
        return sslContext.getSocketFactory();
    }

    private PrivateKey parsePrivateKey(byte[] pemBytes) throws Exception {
        String pem = new String(pemBytes, "UTF-8");
        // Strip PEM headers
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

    public void disconnect() {
        connected = false;
        if (channel != null) {
            try {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "Error shutting down gRPC channel", e);
            }
        }
        Log.i(LOG_TAG, "gRPC channel disconnected");
    }

    public boolean isConnected() {
        return connected;
    }

    // --- Console Info ---

    private void fetchConsoleInfo() {
        try {
            consoleInfo = consoleStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getConsole(Empty.getDefaultInstance());

            machineType = consoleInfo.getMachineType();

            Log.i(LOG_TAG, "Console info received:");
            Log.i(LOG_TAG, "  Machine type: " + machineType);
            Log.i(LOG_TAG, "  Name: " + consoleInfo.getName());
            Log.i(LOG_TAG, "  Speed range: " + consoleInfo.getMinKph() + " - " + consoleInfo.getMaxKph() + " kph");
            Log.i(LOG_TAG, "  Incline range: " + consoleInfo.getMinInclinePercent() + " - " + consoleInfo.getMaxInclinePercent() + "%");
            Log.i(LOG_TAG, "  Can set speed: " + consoleInfo.getCanSetSpeed());
            Log.i(LOG_TAG, "  Can set incline: " + consoleInfo.getCanSetIncline());
            Log.i(LOG_TAG, "  Can set resistance: " + consoleInfo.getCanSetResistance());
            Log.i(LOG_TAG, "  Firmware: " + consoleInfo.getFirmwareVersion());
            Log.i(LOG_TAG, "  Serial: " + consoleInfo.getProductSerialNumber());
            SentryDiagnostics.recordConsoleInfo(consoleInfo, machineType);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to fetch console info", e);
            SentryDiagnostics.recordConsoleInfoFailure(e);
        }
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
                || machineType == ConsoleType.INCLINE_TRAINER;
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
        if (!connected) return;
        new Thread(() -> {
            try {
                SetSpeedRequest req = SetSpeedRequest.newBuilder().setKph(kph).build();
                Result result = speedStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setSpeed(req);
                Log.i(LOG_TAG, "SetSpeed(" + kph + " kph) -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                Log.e(LOG_TAG, "SetSpeed failed: " + e.getStatus(), e);
            } catch (Exception e) {
                Log.e(LOG_TAG, "SetSpeed error", e);
            }
        }).start();
    }

    public void setIncline(double percent) {
        if (!connected) return;
        new Thread(() -> {
            try {
                SetInclineRequest req = SetInclineRequest.newBuilder().setPercent(percent).build();
                Result result = inclineStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setIncline(req);
                Log.i(LOG_TAG, "SetIncline(" + percent + "%) -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                Log.e(LOG_TAG, "SetIncline failed: " + e.getStatus(), e);
            } catch (Exception e) {
                Log.e(LOG_TAG, "SetIncline error", e);
            }
        }).start();
    }

    public void setResistance(double resistance) {
        if (!connected) return;
        new Thread(() -> {
            try {
                SetResistanceRequest req = SetResistanceRequest.newBuilder().setResistance(resistance).build();
                Result result = resistanceStub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .setResistance(req);
                Log.i(LOG_TAG, "SetResistance(" + resistance + ") -> success=" + result.getSuccess());
            } catch (StatusRuntimeException e) {
                Log.e(LOG_TAG, "SetResistance failed: " + e.getStatus(), e);
            } catch (Exception e) {
                Log.e(LOG_TAG, "SetResistance error", e);
            }
        }).start();
    }

    // --- Data Subscriptions ---

    public void startSubscriptions() {
        if (!connected) return;
        Log.i(LOG_TAG, "Starting gRPC data subscriptions");

        subscribeSpeed();
        subscribeIncline();
        subscribeDistance();

        if (isBikeDevice() || isElliptical()) {
            subscribeResistance();
            subscribeCadence();
            subscribeWatts();
        }
    }

    private void subscribeSpeed() {
        speedAsyncStub.speedSubscription(Empty.getDefaultInstance(), new StreamObserver<SpeedData>() {
            @Override
            public void onNext(SpeedData data) {
                lastSpeedKph = data.getLastKph();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Speed subscription error", t);
                retrySubscription(() -> subscribeSpeed(), 3000);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Speed subscription completed");
            }
        });
    }

    private void subscribeIncline() {
        inclineAsyncStub.inclineSubscription(Empty.getDefaultInstance(), new StreamObserver<InclineData>() {
            @Override
            public void onNext(InclineData data) {
                lastInclinePercent = data.getLastInclinePercent();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Incline subscription error", t);
                retrySubscription(() -> subscribeIncline(), 3000);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Incline subscription completed");
            }
        });
    }

    private void subscribeDistance() {
        distanceAsyncStub.distanceSubscription(Empty.getDefaultInstance(), new StreamObserver<DistanceData>() {
            @Override
            public void onNext(DistanceData data) {
                // Use lastDistanceKm (session distance) — totalDistanceKm is a
                // lifetime odometer that counts backwards on some machines
                lastDistanceKm = data.getLastDistanceKm();
                Log.d(LOG_TAG, "Distance: " + lastDistanceKm + " km (" + (int)(lastDistanceKm * 1000) + " m)");
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Distance subscription error", t);
                retrySubscription(() -> subscribeDistance(), 3000);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Distance subscription completed");
            }
        });
    }

    private void subscribeResistance() {
        resistanceAsyncStub.resistanceSubscription(Empty.getDefaultInstance(), new StreamObserver<ResistanceData>() {
            @Override
            public void onNext(ResistanceData data) {
                lastResistance = data.getLastResistance();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Resistance subscription error", t);
                retrySubscription(() -> subscribeResistance(), 3000);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Resistance subscription completed");
            }
        });
    }

    private void subscribeCadence() {
        cadenceAsyncStub.cadenceSubscription(Empty.getDefaultInstance(), new StreamObserver<CadenceData>() {
            @Override
            public void onNext(CadenceData data) {
                lastCadenceRpm = data.getLastRpm();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Cadence subscription error", t);
                retrySubscription(() -> subscribeCadence(), 3000);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Cadence subscription completed");
            }
        });
    }

    private void subscribeWatts() {
        wattsAsyncStub.wattsSubscription(Empty.getDefaultInstance(), new StreamObserver<WattsData>() {
            @Override
            public void onNext(WattsData data) {
                lastWatts = data.getLastWatts();
            }

            @Override
            public void onError(Throwable t) {
                Log.e(LOG_TAG, "Watts subscription error", t);
                retrySubscription(() -> subscribeWatts(), 3000);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG, "Watts subscription completed");
            }
        });
    }

    private void retrySubscription(Runnable subscription, long delayMs) {
        if (!connected) return;
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (connected) {
                    subscription.run();
                }
            } catch (InterruptedException e) {
                // ignore
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
}
