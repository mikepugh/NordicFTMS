package com.nordicftms.app;

import org.junit.Test;

import io.sentry.SentryLevel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NordicFtmsLoggerTest {
    @Test
    public void emitsWhenThrottleWindowHasElapsed() {
        assertTrue(NordicFtmsLogger.shouldEmitNow(0L, 1000L, 500L));
        assertFalse(NordicFtmsLogger.shouldEmitNow(800L, 1000L, 500L));
        assertTrue(NordicFtmsLogger.shouldEmitNow(400L, 1000L, 500L));
    }

    @Test
    public void onlyAttachesInfoBreadcrumbsWhenDetailedTracingIsEnabled() {
        assertFalse(NordicFtmsLogger.shouldAttachBreadcrumb(false, SentryLevel.INFO));
        assertTrue(NordicFtmsLogger.shouldAttachBreadcrumb(true, SentryLevel.INFO));
        assertTrue(NordicFtmsLogger.shouldAttachBreadcrumb(false, SentryLevel.WARNING));
        assertTrue(NordicFtmsLogger.shouldAttachBreadcrumb(false, SentryLevel.ERROR));
    }
}
