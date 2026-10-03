package com.nordicftms.app;

import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.sentry.JsonSerializer;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;

import static org.junit.Assert.*;

public class DiagnosticReportTest {
    @Test
    @SuppressWarnings("unchecked")
    public void sentrySerializationPreservesPackageFlagsAndIndividualRpcFailures() throws Exception {
        Map<String, Object> packageInfo = new LinkedHashMap<>();
        packageInfo.put("availability", "installed");
        packageInfo.put("version_name", "1.2.3");
        packageInfo.put("enabled", false);
        packageInfo.put("stopped_flag", true);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("backend_packages", Collections.singletonMap("com.ifit.glassos_service", packageInfo));
        report.put("tcp_ipv4", Collections.singletonMap("outcome", "refused"));
        Map<String, Object> connection = new LinkedHashMap<>();
        ConsoleDiscoveryDiagnostics discovery = new ConsoleDiscoveryDiagnostics();
        discovery.recordFailure("GetConsole", io.grpc.Status.UNAVAILABLE
                .withCause(new java.net.ConnectException("Connection refused")).asRuntimeException());
        discovery.recordFailure("GetKnownConsoleInfo", io.grpc.Status.UNIMPLEMENTED.asRuntimeException());
        connection.put("rpc_outcomes", discovery.snapshotOutcomes());
        BackendAttemptTrace trace = new BackendAttemptTrace();
        trace.record("grpc_default", "GetConsole", "tcp_refused", 12, "localhost:54321");
        connection.put("attempts", trace.snapshot());
        connection.put("endpoint_history", Collections.singletonList(Collections.singletonMap("presented_peer_sha256", "test-peer-hash")));
        connection.put("tls", Collections.singletonMap("client", Collections.singletonMap("sha256", "test-client-hash")));
        connection.put("discovery", Collections.singletonMap("probe_outcomes", Collections.singletonMap("refused", 10)));

        SentryEvent event = DiagnosticReport.createEvent("NFT-01234567890123456789", true,
                "backend_unavailable", true, DiagnosticReport.runtime(new ServiceStatusSnapshot()),
                connection, report);
        JsonSerializer serializer = new JsonSerializer(new SentryOptions());
        StringWriter json = new StringWriter();
        serializer.serialize(event, json);
        SentryEvent restored = serializer.deserialize(new StringReader(json.toString()), SentryEvent.class);
        assertNotNull(restored);
        assertEquals("NFT-01234567890123456789", restored.getTag("support_id"));
        assertEquals("backend_unavailable", restored.getTag("diagnostic_reason"));
        Map<String, Object> restoredReport = (Map<String, Object>) restored.getContexts().get("support_diagnostics");
        Map<String, Object> packages = (Map<String, Object>) restoredReport.get("backend_packages");
        Map<String, Object> glassos = (Map<String, Object>) packages.get("com.ifit.glassos_service");
        assertEquals(false, glassos.get("enabled"));
        assertEquals(true, glassos.get("stopped_flag"));
        assertEquals("1.2.3", glassos.get("version_name"));
        assertTrue(json.toString().contains("Connection refused"));
        assertTrue(json.toString().contains("UNIMPLEMENTED"));
        Map<String, Object> restoredConnection = (Map<String, Object>) restored.getContexts().get("glassos_connection");
        Map<String, Object> tls = (Map<String, Object>) restoredConnection.get("tls");
        assertEquals("test-client-hash", ((Map<String, Object>) tls.get("client")).get("sha256"));
        Map<String, Object> attempt = (Map<String, Object>) ((java.util.List<?>) restoredConnection.get("attempts")).get(0);
        assertEquals("tcp_refused", attempt.get("outcome"));
        Map<String, Object> history = (Map<String, Object>) ((java.util.List<?>) restoredConnection.get("endpoint_history")).get(0);
        assertEquals("test-peer-hash", history.get("presented_peer_sha256"));
        Map<String, Object> restoredDiscovery = (Map<String, Object>) restoredConnection.get("discovery");
        assertEquals(10, ((Number) ((Map<String, Object>) restoredDiscovery.get("probe_outcomes")).get("refused")).intValue());
    }
}
