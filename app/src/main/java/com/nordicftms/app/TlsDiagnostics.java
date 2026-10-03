package com.nordicftms.app;

import android.annotation.SuppressLint;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Observes the real gRPC TLS connection; never alters trust or hostname verification. */
final class TlsDiagnostics extends SSLSocketFactory {
    interface Listener { void completed(Map<String, Object> details); }

    private final SSLSocketFactory delegate;
    private final Listener listener;

    TlsDiagnostics(SSLSocketFactory delegate, Listener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    static Map<String, Object> certificate(X509Certificate cert) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : hash) fingerprint.append(String.format(Locale.US, "%02x", value & 0xff));
        result.put("sha256", fingerprint.toString());
        result.put("not_before_ms", cert.getNotBefore().getTime());
        result.put("not_after_ms", cert.getNotAfter().getTime());
        long now = System.currentTimeMillis();
        result.put("valid_at_device_time", now >= cert.getNotBefore().getTime() && now <= cert.getNotAfter().getTime());
        return result;
    }

    // All trust decisions and rejections are delegated unchanged to the configured CA trust manager.
    @SuppressLint("CustomX509TrustManager")
    static TrustManager[] observeTrust(TrustManager[] managers, Listener listener) {
        TrustManager[] result = managers.clone();
        for (int index = 0; index < result.length; index++) {
            if (!(result[index] instanceof X509TrustManager)) continue;
            X509TrustManager original = (X509TrustManager) result[index];
            result[index] = new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return original.getAcceptedIssuers(); }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    original.checkClientTrusted(chain, authType);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    Map<String, Object> details = new LinkedHashMap<>();
                    try {
                        if (chain != null && chain.length > 0) details.put("presented_peer_leaf", certificate(chain[0]));
                    } catch (Exception ignored) { }
                    try {
                        original.checkServerTrusted(chain, authType);
                        details.put("server_chain_validation", "accepted");
                    } catch (CertificateException error) {
                        details.put("server_chain_validation", "rejected");
                        details.put("validation_error", ConsoleDiscoveryDiagnostics.describeError(error));
                        throw error;
                    } finally {
                        try { listener.completed(details); } catch (RuntimeException ignored) { }
                    }
                }
            };
        }
        return result;
    }

    private Socket observe(Socket socket) {
        long started = System.nanoTime();
        if (socket instanceof SSLSocket) {
            ((SSLSocket) socket).addHandshakeCompletedListener(event -> {
                try {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("handshake", "completed");
                    result.put("at_ms", System.currentTimeMillis());
                    result.put("duration_ms", (System.nanoTime() - started) / 1_000_000L);
                    result.put("protocol", event.getSession().getProtocol());
                    result.put("cipher_suite", event.getCipherSuite());
                    result.put("peer_leaf", certificate((X509Certificate) event.getPeerCertificates()[0]));
                    listener.completed(result);
                } catch (Exception ignored) {
                    // A diagnostics failure must not break a working transport.
                }
            });
        }
        return socket;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return observe(delegate.createSocket()); }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return observe(delegate.createSocket(socket, host, port, autoClose));
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return observe(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return observe(delegate.createSocket(host, port, local, localPort));
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return observe(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return observe(delegate.createSocket(host, port, local, localPort));
    }
}
