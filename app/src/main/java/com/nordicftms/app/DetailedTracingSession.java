package com.nordicftms.app;

/** A persisted wall-clock deadline bounded by a monotonic clock within a process. */
final class DetailedTracingSession {
    static final long DURATION_MS = 30 * 60_000L;
    private long observedExpiryMs;
    private long elapsedDeadlineMs;

    long remainingMs(long expiresAtMs, long nowMs, long elapsedMs) {
        long wallRemainingMs = expiresAtMs - nowMs;
        // Missing deadlines (including old enabled settings) and clock rollback fail closed.
        if (expiresAtMs <= 0 || wallRemainingMs <= 0 || wallRemainingMs > DURATION_MS) {
            return 0;
        }
        if (observedExpiryMs != expiresAtMs) {
            observedExpiryMs = expiresAtMs;
            elapsedDeadlineMs = elapsedMs + wallRemainingMs;
        }
        return Math.max(0, Math.min(wallRemainingMs, elapsedDeadlineMs - elapsedMs));
    }

    void clear() {
        observedExpiryMs = 0;
        elapsedDeadlineMs = 0;
    }
}
