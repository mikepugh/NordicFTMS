package com.nordicftms.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticReportLimiterTest {
    @Test
    public void startupReportDoesNotSuppressFirstBackendFailure() {
        DiagnosticReportLimiter limiter = new DiagnosticReportLimiter();
        assertTrue(limiter.acquire("app_start", 0L, false));
        assertFalse(limiter.acquire("backend_unavailable", 1L, false));
        limiter.release();
        assertTrue(limiter.acquire("backend_unavailable", 2L, false));
        limiter.release();
        assertFalse(limiter.acquire("backend_unavailable", 3L, false));
        assertTrue(limiter.acquire("backend_unavailable", 600_002L, false));
    }

    @Test
    public void manualReportHasIndependentCooldownAndCannotOverlapCollection() {
        DiagnosticReportLimiter limiter = new DiagnosticReportLimiter();
        assertTrue(limiter.acquire("backend_unavailable", 0L, false));
        assertFalse(limiter.acquire("manual", 1L, true));
        limiter.release();
        assertTrue(limiter.acquire("manual", 2L, true));
        limiter.release();
        assertFalse(limiter.acquire("manual", 30_001L, true));
        assertTrue(limiter.acquire("manual", 30_002L, true));
    }
}
