package com.nordicftms.app;

import org.junit.Test;
import java.net.ConnectException;
import io.grpc.Status;
import static org.junit.Assert.*;

public class ConsoleDiscoveryDiagnosticsTest {
    @Test
    public void capturedOutcomesSurviveSubsequentRetries() {
        ConsoleDiscoveryDiagnostics diagnostics = new ConsoleDiscoveryDiagnostics();
        diagnostics.recordFailure("GetConsole", Status.UNAVAILABLE.asRuntimeException());
        java.util.Map<String, String> captured = diagnostics.snapshotOutcomes();
        diagnostics.recordResponse("GetConsole", true);
        assertTrue(captured.get("GetConsole").contains("UNAVAILABLE"));
        assertEquals("ready", diagnostics.snapshotOutcomes().get("GetConsole"));
    }

    @Test
    public void optionalUnsupportedRpcDoesNotHidePrimaryTransportFailure() {
        ConsoleDiscoveryDiagnostics diagnostics = new ConsoleDiscoveryDiagnostics();
        Throwable transport = Status.UNAVAILABLE.withDescription("io exception")
                .withCause(new ConnectException("Connection refused")).asRuntimeException();
        Throwable unsupported = Status.UNIMPLEMENTED.asRuntimeException();
        diagnostics.recordFailure("GetConsole", transport);
        diagnostics.recordFailure("GetKnownConsoleInfo", unsupported);

        assertTrue(diagnostics.describe().contains("GetConsole: UNAVAILABLE"));
        assertTrue(diagnostics.describe().contains("Connection refused"));
        assertTrue(diagnostics.describe().contains("GetKnownConsoleInfo: UNIMPLEMENTED"));
        assertArrayEquals(new Throwable[]{transport, unsupported}, diagnostics.asException().getSuppressed());
        assertTrue(diagnostics.isUnsupported("GetKnownConsoleInfo"));
        assertFalse(diagnostics.isUnsupported("GetConsole"));
    }

    @Test
    public void successfulEmptyResponseReplacesOldTransportFailure() {
        ConsoleDiscoveryDiagnostics diagnostics = new ConsoleDiscoveryDiagnostics();
        diagnostics.recordFailure("GetConsole", Status.UNAVAILABLE.asRuntimeException());
        diagnostics.recordResponse("GetConsole", false);
        assertTrue(diagnostics.describe().contains("GetConsole: returned empty/incomplete console info"));
        assertFalse(diagnostics.describe().contains("UNAVAILABLE"));
        assertEquals(0, diagnostics.asException().getSuppressed().length);
    }

    @Test
    public void newConnectionRetriesMethodsUnsupportedByPreviousBackend() {
        ConsoleDiscoveryDiagnostics previous = new ConsoleDiscoveryDiagnostics();
        previous.recordFailure("ConsoleChanged", Status.UNIMPLEMENTED.asException());
        assertTrue(previous.isUnsupported("ConsoleChanged"));
        assertFalse(new ConsoleDiscoveryDiagnostics().isUnsupported("ConsoleChanged"));
    }

    @Test
    public void recordsStreamRecoveryWithoutLosingUnaryResults() {
        ConsoleDiscoveryDiagnostics diagnostics = new ConsoleDiscoveryDiagnostics();
        diagnostics.recordResponse("GetConsole", false);
        diagnostics.recordFailure("ConsoleChanged", Status.UNAVAILABLE.asRuntimeException());
        diagnostics.recordResponse("ConsoleChanged", true);
        assertTrue(diagnostics.describe().contains("ConsoleChanged: ready"));
        assertTrue(diagnostics.describe().contains("GetConsole: returned empty"));
        assertEquals(0, diagnostics.asException().getSuppressed().length);
    }
}
