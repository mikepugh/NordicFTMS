package com.nordicftms.app;

import org.junit.Test;

import io.sentry.SentryLevel;
import io.sentry.SentryEvent;
import io.sentry.JsonSerializer;
import io.sentry.SentryOptions;
import java.io.StringWriter;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

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

    @Test
    public void eventRetainsDiagnosticTagsMessageAndOriginalFailure() throws Exception {
        Throwable failure = new IllegalStateException("backend refused");
        SentryEvent event = NordicFtmsLogger.createEvent(SentryLevel.WARNING,
                "grpc_backend_not_ready", "GlassOS unavailable", failure,
                Collections.singletonMap("grpc_source", "GetConsole"), true);
        assertSame(failure, event.getThrowable());
        assertEquals(SentryLevel.WARNING, event.getLevel());
        assertEquals("grpc_backend_not_ready", event.getTag("logger_event"));
        assertEquals("true", event.getTag("detailed_tracing_enabled"));
        assertEquals("GetConsole", event.getTag("grpc_source"));
        StringWriter output = new StringWriter();
        new JsonSerializer(new SentryOptions()).serialize(event, output);
        assertTrue(output.toString().contains("grpc_backend_not_ready"));
        assertTrue(output.toString().contains("GlassOS unavailable"));
    }
}
