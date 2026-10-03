package com.nordicftms.app;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Finds TCP candidates only. A listener is never evidence that GlassOS is ready. */
final class LoopbackBackendDiscovery {
    static final int DEFAULT_PORT = 54321;
    static final int MAX_CANDIDATES = 64;
    static final int MAX_PROBES = 8192;
    static final long SCAN_BUDGET_MS = 4000;
    static final long SCAN_COOLDOWN_MS = 30_000;
    static final long VALIDATION_COOLDOWN_MS = 60_000;
    private static final int MAX_TABLE_ROWS = 4096;
    private static final int ENDPOINT_COUNT = 65535 * 2;

    interface Probe { Map<String, Object> connect(String host, int port, int timeoutMs); }
    interface Tables { Reader open(boolean ipv6) throws IOException; }
    interface Clock { long nowMs(); }

    static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            if (!("127.0.0.1".equals(host) || "::1".equals(host)) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Only literal loopback TCP endpoints are allowed");
            }
            this.host = host;
            this.port = port;
        }

        @Override public String toString() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
        @Override public boolean equals(Object other) {
            return other instanceof Endpoint && host.equals(((Endpoint) other).host) && port == ((Endpoint) other).port;
        }
        @Override public int hashCode() { return 31 * host.hashCode() + port; }
    }

    static final class Result {
        final List<Endpoint> candidates;
        final Map<String, Object> details;

        Result(List<Endpoint> candidates, Map<String, Object> details) {
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    private final Probe probe;
    private final Tables tables;
    private final Clock clock;
    private final Map<Endpoint, Long> candidates = new LinkedHashMap<>();
    private int nextOffset;
    private int scannedInSweep;
    private long completedSweeps;
    private long nextScanAt = Long.MIN_VALUE;
    private boolean candidateLimitReached;

    LoopbackBackendDiscovery() {
        this(BackendPortProbe::probe,
                ipv6 -> new FileReader(ipv6 ? "/proc/net/tcp6" : "/proc/net/tcp"),
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    LoopbackBackendDiscovery(Probe probe, Tables tables, Clock clock) {
        this.probe = probe;
        this.tables = tables;
        this.clock = clock;
    }

    Result discover() {
        Map<String, Object> details = new LinkedHashMap<>();
        long started = clock.nowMs();
        candidateLimitReached = false;
        // Try both address families even if localhost resolution selected the wrong listener.
        remember(new Endpoint("127.0.0.1", DEFAULT_PORT));
        remember(new Endpoint("::1", DEFAULT_PORT));
        boolean ipv4Complete = readTable(false, details);
        boolean ipv6Complete = readTable(true, details);
        int probes = 0;
        int reachable = 0;
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        String scanOutcome;
        if (Thread.currentThread().isInterrupted()) {
            scanOutcome = "interrupted";
        } else if (ipv4Complete && ipv6Complete) {
            scanOutcome = "listener_tables_available";
        } else if (started < nextScanAt) {
            scanOutcome = "cooldown";
        } else {
            scanOutcome = "partial";
            while (probes < MAX_PROBES && clock.nowMs() - started < SCAN_BUDGET_MS) {
                if (Thread.currentThread().isInterrupted()) {
                    scanOutcome = "interrupted";
                    break;
                }
                Endpoint endpoint = endpointAt(nextOffset);
                int remainingMs = (int) Math.max(1, SCAN_BUDGET_MS - (clock.nowMs() - started));
                Map<String, Object> result = probe.connect(endpoint.host, endpoint.port, Math.min(25, remainingMs));
                String outcome = String.valueOf(result.get("outcome"));
                Integer count = outcomes.get(outcome);
                outcomes.put(outcome, count == null ? 1 : count + 1);
                probes++;
                nextOffset = (nextOffset + 1) % ENDPOINT_COUNT;
                scannedInSweep++;
                if ("reachable".equals(outcome)) {
                    reachable++;
                    remember(endpoint);
                }
                if (scannedInSweep == ENDPOINT_COUNT) {
                    scannedInSweep = 0;
                    completedSweeps++;
                    scanOutcome = "sweep_complete";
                    break;
                }
            }
            nextScanAt = clock.nowMs() + SCAN_COOLDOWN_MS;
        }
        details.put("scope", "loopback_only");
        details.put("scan_outcome", scanOutcome);
        details.put("probes_this_pass", probes);
        details.put("reachable_this_pass", reachable);
        details.put("probe_outcomes", outcomes);
        details.put("scanned_in_sweep", scannedInSweep);
        details.put("endpoints_per_sweep", ENDPOINT_COUNT);
        details.put("completed_sweeps", completedSweeps);
        details.put("next_endpoint", endpointAt(nextOffset).toString());
        details.put("duration_ms", Math.max(0, clock.nowMs() - started));
        details.put("candidate_limit_reached", candidateLimitReached);
        details.put("candidate_count", candidates.size());
        List<Endpoint> ready = new ArrayList<>();
        for (Map.Entry<Endpoint, Long> candidate : candidates.entrySet()) {
            if (candidate.getValue() <= clock.nowMs()) ready.add(candidate.getKey());
        }
        details.put("candidates_due", ready.size());
        return new Result(ready, details);
    }

    void validationAttempted(Endpoint endpoint) {
        // Move attempted endpoints to the end so unrelated listeners cannot starve later candidates.
        candidates.remove(endpoint);
        candidates.put(endpoint, clock.nowMs() + VALIDATION_COOLDOWN_MS);
    }

    private void remember(Endpoint endpoint) {
        if (candidates.containsKey(endpoint)) return;
        if (candidates.size() >= MAX_CANDIDATES) {
            candidateLimitReached = true;
            return;
        }
        candidates.put(endpoint, Long.MIN_VALUE);
    }

    static Endpoint endpointAt(int offset) {
        int port = ((DEFAULT_PORT - 1 + offset / 2) % 65535) + 1;
        return new Endpoint(offset % 2 == 0 ? "127.0.0.1" : "::1", port);
    }

    private boolean readTable(boolean ipv6, Map<String, Object> details) {
        String key = ipv6 ? "tcp6_table" : "tcp4_table";
        try (BufferedReader reader = new BufferedReader(tables.open(ipv6))) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    details.put(key, "interrupted");
                    return false;
                }
                if (++rows > MAX_TABLE_ROWS) {
                    details.put(key, "truncated");
                    return false;
                }
                Endpoint endpoint = parseListener(line, ipv6);
                if (endpoint != null) remember(endpoint);
            }
            details.put(key, "readable");
            return true;
        } catch (IOException | SecurityException error) {
            details.put(key, "unavailable");
            details.put(key + "_error", ConsoleDiscoveryDiagnostics.describeError(error));
            return false;
        }
    }

    static Endpoint parseListener(String line, boolean ipv6) {
        String[] columns = line.trim().split("\\s+");
        if (columns.length < 4 || !"0A".equalsIgnoreCase(columns[3])) return null;
        String[] address = columns[1].split(":");
        if (address.length != 2) return null;
        String hex = address[0];
        boolean local = ipv6
                ? hex.equals("00000000000000000000000000000000")
                    || hex.equals("00000000000000000000000001000000")
                : hex.equals("00000000") || hex.equalsIgnoreCase("0100007F");
        if (!local) return null;
        try {
            return new Endpoint(ipv6 ? "::1" : "127.0.0.1", Integer.parseInt(address[1], 16));
        } catch (IllegalArgumentException invalidRow) {
            return null;
        }
    }
}
