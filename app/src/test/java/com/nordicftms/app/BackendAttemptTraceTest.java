package com.nordicftms.app;

import org.junit.Test;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import io.grpc.Status;
import static org.junit.Assert.*;

public class BackendAttemptTraceTest {
    @Test public void classifiesCauseRatherThanGenericGrpcStatus() {
        assertEquals("tcp_refused", BackendAttemptTrace.classify(Status.UNAVAILABLE
                .withCause(new ConnectException("ECONNREFUSED (Connection refused)")).asRuntimeException()));
        assertEquals("tcp_connect_failed", BackendAttemptTrace.classify(new ConnectException("Network is unreachable")));
        assertEquals("tls_handshake_or_certificate", BackendAttemptTrace.classify(Status.UNAVAILABLE
                .withCause(new SSLHandshakeException("certificate rejected")).asRuntimeException()));
        assertEquals("transport_timeout", BackendAttemptTrace.classify(new SocketTimeoutException()));
        assertEquals("rpc_unimplemented", BackendAttemptTrace.classify(Status.UNIMPLEMENTED.asException()));
        assertEquals("rpc_access_denied", BackendAttemptTrace.classify(Status.PERMISSION_DENIED.asException()));
        assertEquals("rpc_deadline_exceeded", BackendAttemptTrace.classify(Status.DEADLINE_EXCEEDED.asException()));
        assertEquals("transport_unavailable", BackendAttemptTrace.classify(Status.UNAVAILABLE.asException()));
        assertEquals("unknown", BackendAttemptTrace.classify(null));
        ConsoleDiscoveryDiagnostics discovery = new ConsoleDiscoveryDiagnostics();
        discovery.recordFailure("GetConsole", Status.UNAVAILABLE.withCause(new SSLHandshakeException("bad chain")).asException());
        assertEquals("tls_handshake_or_certificate", BackendAttemptTrace.classify(discovery.asException()));
    }

    @Test public void preservesEarlierFailureWhenAnotherPathWorksAndBoundsHistory() {
        BackendAttemptTrace trace = new BackendAttemptTrace();
        trace.record("grpc_tcp", "GetConsole", "tcp_refused", 20, "connection refused");
        trace.record("grpc_discovered", "GetConsole", "ready", 500, "127.0.0.1:54322");
        List<Map<String, Object>> snapshot = trace.snapshot();
        assertEquals("tcp_refused", snapshot.get(0).get("outcome"));
        assertEquals("ready", snapshot.get(1).get("outcome"));
        for (int i = 0; i < 60; i++) trace.record("grpc_tcp", "connect", "failed", 0, "retry");
        assertEquals(48, trace.snapshot().size());
        assertEquals(2, snapshot.size());
        assertTrue((Long) trace.snapshot().get(0).get("sequence") > 2);
    }
}
