package com.nordicftms.app;

import java.util.HashMap;
import java.util.Map;

/** Coalesces concurrent requests and limits repeated reports during a retry loop. */
final class DiagnosticReportLimiter {
    private final Map<String, Long> lastReports = new HashMap<>();
    private boolean collecting;

    synchronized boolean acquire(String reason, long nowMs, boolean manual) {
        Long last = lastReports.get(reason);
        long interval = manual ? 30_000L : 600_000L;
        if (collecting || (last != null && nowMs - last < interval)) return false;
        collecting = true;
        lastReports.put(reason, nowMs);
        return true;
    }

    synchronized void release() {
        collecting = false;
    }
}
