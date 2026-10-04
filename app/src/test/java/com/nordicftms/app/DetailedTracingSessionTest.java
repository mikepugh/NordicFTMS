package com.nordicftms.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DetailedTracingSessionTest {
    private static final long START_MS = 10_000_000L;
    private static final long EXPIRY_MS = START_MS + DetailedTracingSession.DURATION_MS;

    @Test
    public void expiresAtThirtyMinutesIncludingTimeAsleep() {
        DetailedTracingSession session = new DetailedTracingSession();
        assertEquals(DetailedTracingSession.DURATION_MS, session.remainingMs(EXPIRY_MS, START_MS, 100L));
        assertEquals(1L, session.remainingMs(EXPIRY_MS, EXPIRY_MS - 1, 100L + DetailedTracingSession.DURATION_MS - 1));
        assertEquals(0L, session.remainingMs(EXPIRY_MS, EXPIRY_MS, 100L + DetailedTracingSession.DURATION_MS));
    }

    @Test
    public void restartResumesOnlyTheRemainingTime() {
        DetailedTracingSession restarted = new DetailedTracingSession();
        assertEquals(600_000L, restarted.remainingMs(EXPIRY_MS, EXPIRY_MS - 600_000L, 50L));
        assertEquals(0L, restarted.remainingMs(EXPIRY_MS, EXPIRY_MS + 1, 51L));
    }

    @Test
    public void oldEnabledSettingWithoutDeadlineIsDisabled() {
        assertEquals(0L, new DetailedTracingSession().remainingMs(0L, START_MS, 100L));
    }

    @Test
    public void wallClockRollbackCannotExtendAnActiveSession() {
        DetailedTracingSession session = new DetailedTracingSession();
        session.remainingMs(EXPIRY_MS, START_MS, 100L);
        assertEquals(0L, session.remainingMs(EXPIRY_MS, EXPIRY_MS - 60_000L,
                100L + DetailedTracingSession.DURATION_MS));
        assertEquals(0L, new DetailedTracingSession().remainingMs(EXPIRY_MS, START_MS - 1, 100L));
    }

    @Test
    public void reenableStartsAFreshSession() {
        DetailedTracingSession session = new DetailedTracingSession();
        session.remainingMs(EXPIRY_MS, START_MS, 100L);
        session.clear();
        long newStartMs = EXPIRY_MS + 1;
        assertEquals(DetailedTracingSession.DURATION_MS, session.remainingMs(
                newStartMs + DetailedTracingSession.DURATION_MS, newStartMs, 2_000_000L));
    }
}
