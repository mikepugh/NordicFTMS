package com.nordicftms.app;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;

import io.grpc.Status;

/** Bounded, chronological evidence, including failed paths before a fallback succeeds. */
final class BackendAttemptTrace {
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private long sequence;

    synchronized void record(String path, String stage, String outcome, long durationMs, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sequence", ++sequence);
        entry.put("at_ms", System.currentTimeMillis());
        entry.put("path", path);
        entry.put("stage", stage);
        entry.put("outcome", outcome);
        entry.put("duration_ms", Math.max(0, durationMs));
        entry.put("detail", detail == null ? "" : detail.substring(0, Math.min(detail.length(), 2000)));
        entries.add(Collections.unmodifiableMap(entry));
        if (entries.size() > 48) entries.remove(0);
    }

    synchronized List<Map<String, Object>> snapshot() {
        return new ArrayList<>(entries);
    }

    static String classify(Throwable error) {
        if (error == null) return "unknown";
        boolean tls = false;
        boolean connect = false;
        boolean timeout = false;
        Status.Code code = Status.Code.UNKNOWN;
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(error);
        for (int depth = 0; !pending.isEmpty() && depth < 24; depth++) {
            Throwable cause = pending.removeFirst();
            String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.US);
            if (cause instanceof SSLException || cause instanceof CertificateException) tls = true;
            if (text.contains("econnrefused") || text.contains("connection refused")) return "tcp_refused";
            if (cause instanceof ConnectException) connect = true;
            if (cause instanceof SocketTimeoutException) timeout = true;
            Status.Code observed = Status.fromThrowable(cause).getCode();
            if (observed != Status.Code.UNKNOWN && code == Status.Code.UNKNOWN) code = observed;
            if (cause.getCause() != null && cause.getCause() != cause) pending.addLast(cause.getCause());
            for (Throwable suppressed : cause.getSuppressed()) {
                if (pending.size() < 24) pending.addLast(suppressed);
            }
        }
        if (tls) return "tls_handshake_or_certificate";
        if (connect) return "tcp_connect_failed";
        if (timeout) return "transport_timeout";
        if (code == Status.Code.UNIMPLEMENTED) return "rpc_unimplemented";
        if (code == Status.Code.UNAUTHENTICATED || code == Status.Code.PERMISSION_DENIED) return "rpc_access_denied";
        if (code == Status.Code.DEADLINE_EXCEEDED) return "rpc_deadline_exceeded";
        if (code == Status.Code.UNAVAILABLE) return "transport_unavailable";
        return "unknown";
    }
}
