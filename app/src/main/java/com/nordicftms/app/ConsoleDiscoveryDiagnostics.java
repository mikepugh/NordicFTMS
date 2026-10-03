package com.nordicftms.app;

import java.util.LinkedHashMap;
import java.util.Map;

import io.grpc.Status;

/** Keeps each discovery result so an optional fallback cannot hide the primary failure. */
final class ConsoleDiscoveryDiagnostics {
    private final Map<String, String> outcomes = new LinkedHashMap<>();
    private final Map<String, Throwable> failures = new LinkedHashMap<>();

    ConsoleDiscoveryDiagnostics() {
        outcomes.put("GetConsole", "not attempted");
        outcomes.put("GetKnownConsoleInfo", "not attempted");
        outcomes.put("ConsoleChanged", "no console info received");
    }

    synchronized void recordResponse(String source, boolean usable) {
        outcomes.put(source, usable ? "ready" : "returned empty/incomplete console info");
        failures.remove(source);
    }

    synchronized void recordFailure(String source, Throwable error) {
        failures.put(source, error);
        outcomes.put(source, describeError(error));
    }

    synchronized void recordStreamCompleted() {
        outcomes.put("ConsoleChanged", "stream completed; resubscribing");
    }

    synchronized boolean isUnsupported(String source) {
        Throwable error = failures.get(source);
        return error != null && Status.fromThrowable(error).getCode() == Status.Code.UNIMPLEMENTED;
    }

    synchronized String describe() {
        StringBuilder message = new StringBuilder();
        for (Map.Entry<String, String> entry : outcomes.entrySet()) {
            if (message.length() > 0) message.append("; ");
            message.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return message.toString();
    }

    synchronized Map<String, String> snapshotOutcomes() {
        return new LinkedHashMap<>(outcomes);
    }

    synchronized Throwable asException() {
        IllegalStateException error = new IllegalStateException(describe());
        for (Throwable failure : failures.values()) error.addSuppressed(failure);
        return error;
    }

    static String describeError(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        // Include the transport cause (for example TLS or connection refused), not just UNAVAILABLE.
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (message.length() > 0) message.append(" caused by ");
            String detail = current.getMessage();
            message.append(detail == null || detail.isEmpty() ? current.getClass().getSimpleName() : detail);
            if (detail != null && !detail.isEmpty()) message.append(" [").append(current.getClass().getSimpleName()).append("]");
            current = current.getCause();
        }
        return message.length() > 0 ? message.toString() : "unknown error";
    }
}
