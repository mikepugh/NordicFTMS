package com.nordicftms.app;

import org.junit.Test;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import static org.junit.Assert.*;

public class TlsDiagnosticsTest {
    @Test public void preservesCertificateRejectionAndRecordsFailureEvenIfObserverThrows() throws Exception {
        CertificateException rejection = new CertificateException("untrusted test certificate");
        X509TrustManager original = manager(rejection);
        AtomicReference<Map<String, Object>> observed = new AtomicReference<>();
        X509TrustManager wrapped = (X509TrustManager) TlsDiagnostics.observeTrust(new TrustManager[]{original}, details -> {
            observed.set(details);
            throw new IllegalStateException("diagnostic observer failed");
        })[0];
        try {
            wrapped.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("Untrusted certificates must stay rejected");
        } catch (CertificateException error) {
            assertSame(rejection, error);
        }
        assertEquals("rejected", observed.get().get("server_chain_validation"));
        assertTrue(observed.get().get("validation_error").toString().contains("untrusted test certificate"));
    }

    @Test public void acceptedChainIsNotClaimedToBeACompletedHandshake() throws Exception {
        AtomicReference<Map<String, Object>> observed = new AtomicReference<>();
        X509TrustManager wrapped = (X509TrustManager) TlsDiagnostics.observeTrust(
                new TrustManager[]{manager(null)}, observed::set)[0];
        wrapped.checkServerTrusted(new X509Certificate[0], "RSA");
        assertEquals("accepted", observed.get().get("server_chain_validation"));
        assertFalse(observed.get().containsKey("handshake"));
    }

    private static X509TrustManager manager(CertificateException rejection) {
        return new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (rejection != null) throw rejection;
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (rejection != null) throw rejection;
            }
        };
    }
}
