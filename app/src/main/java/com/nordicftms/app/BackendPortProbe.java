package com.nordicftms.app;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

final class BackendPortProbe {
    private BackendPortProbe() { }

    static Map<String, Object> probe(String address, int port, int timeoutMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        long started = System.nanoTime();
        result.put("address", address);
        result.put("port", port);
        // Connect and close only: this does not authenticate, invoke RPCs, or control equipment.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            result.put("outcome", "reachable");
        } catch (SocketTimeoutException error) {
            result.put("outcome", "timeout");
        } catch (ConnectException error) {
            result.put("outcome", "tcp_refused".equals(BackendAttemptTrace.classify(error)) ? "refused" : "connect_failed");
            result.put("error", ConsoleDiscoveryDiagnostics.describeError(error));
        } catch (IOException | SecurityException error) {
            result.put("outcome", "error");
            result.put("error", ConsoleDiscoveryDiagnostics.describeError(error));
        }
        result.put("duration_ms", (System.nanoTime() - started) / 1_000_000L);
        return result;
    }
}
