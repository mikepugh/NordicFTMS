package com.nordicftms.app;

import android.content.Context;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;

public final class NordicFtmsLogger {
    private static final String LOG_TAG = "NordicFTMS";
    private static final String TRACE_TAG = "NordicFTMS-Trace";
    private static final long DEFAULT_SENTRY_THROTTLE_MS = 60_000L;

    private static final NordicFtmsLogger INSTANCE = new NordicFtmsLogger();

    private final Map<String, Long> sentryThrottle = new ConcurrentHashMap<>();

    private NordicFtmsLogger() {
    }

    public static NordicFtmsLogger getInstance() {
        return INSTANCE;
    }

    public void debug(Context context, String eventName, String message) {
        log(context, SentryLevel.DEBUG, eventName, message, null, Collections.emptyMap());
    }

    public void info(Context context, String eventName, String message) {
        log(context, SentryLevel.INFO, eventName, message, null, Collections.emptyMap());
    }

    public void info(Context context, String eventName, String message, Map<String, String> tags) {
        log(context, SentryLevel.INFO, eventName, message, null, tags);
    }

    public void warn(Context context, String eventName, String message) {
        log(context, SentryLevel.WARNING, eventName, message, null, Collections.emptyMap());
    }

    public void warn(Context context, String eventName, String message, Throwable throwable) {
        log(context, SentryLevel.WARNING, eventName, message, throwable, Collections.emptyMap());
    }

    public void error(Context context, String eventName, String message, Throwable throwable) {
        log(context, SentryLevel.ERROR, eventName, message, throwable, Collections.emptyMap());
    }

    public void error(Context context, String eventName, String message, Throwable throwable, Map<String, String> tags) {
        log(context, SentryLevel.ERROR, eventName, message, throwable, tags);
    }

    /**
     * High-frequency diagnostic trace that intentionally bypasses Sentry
     * breadcrumbs and {@code captureMessage}. Active only when the user has
     * enabled detailed tracing. Use for per-event detail (every incline command,
     * every observation, every tracker decision) that would otherwise evict
     * real crash context from Sentry's 100-entry breadcrumb buffer.
     *
     * <p>Output goes to Android {@code Log.i} under the "NordicFTMS-Trace" tag
     * so it can be filtered separately: {@code adb logcat -s NordicFTMS-Trace}.
     */
    public void trace(Context context, String eventName, String message) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null || !NordicFtmsPreferences.isDetailedTracingEnabled(appContext)) {
            return;
        }
        Log.i(TRACE_TAG, "[" + eventName + "] " + message);
    }

    static boolean shouldEmitNow(long lastEmittedAtMs, long nowMs, long throttleMs) {
        return lastEmittedAtMs <= 0L || nowMs - lastEmittedAtMs >= throttleMs;
    }

    static boolean shouldAttachBreadcrumb(boolean detailedTracingEnabled, SentryLevel level) {
        return detailedTracingEnabled || level == SentryLevel.WARNING || level == SentryLevel.ERROR;
    }

    private void log(
            Context context,
            SentryLevel level,
            String eventName,
            String message,
            Throwable throwable,
            Map<String, String> tags
    ) {
        String renderedMessage = "[" + eventName + "] " + message;

        if (level == SentryLevel.DEBUG) {
            Log.d(LOG_TAG, renderedMessage, throwable);
        } else if (level == SentryLevel.INFO) {
            Log.i(LOG_TAG, renderedMessage, throwable);
        } else if (level == SentryLevel.WARNING) {
            Log.w(LOG_TAG, renderedMessage, throwable);
        } else {
            Log.e(LOG_TAG, renderedMessage, throwable);
        }

        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext != null) {
            NordicFtmsPreferences.syncStatusSnapshot(appContext);
        }

        boolean detailedTracingEnabled = appContext != null
                && NordicFtmsPreferences.isDetailedTracingEnabled(appContext);

        if (level == SentryLevel.WARNING || level == SentryLevel.ERROR) {
            NordicFtmsStatusStore.getInstance().update(snapshot -> {
                snapshot.lastError.level = level.name();
                snapshot.lastError.eventName = eventName;
                snapshot.lastError.message = message;
                snapshot.lastError.timestampMs = System.currentTimeMillis();
            });
        }

        if (shouldAttachBreadcrumb(detailedTracingEnabled, level)) {
            Breadcrumb breadcrumb = new Breadcrumb();
            breadcrumb.setCategory(eventName);
            breadcrumb.setLevel(level);
            breadcrumb.setMessage(message);
            if (tags != null) {
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    breadcrumb.setData(entry.getKey(), entry.getValue());
                }
            }
            Sentry.addBreadcrumb(breadcrumb);
        }

        if (level != SentryLevel.WARNING && level != SentryLevel.ERROR) {
            return;
        }

        String dedupeKey = eventName + "|" + level.name();
        long nowMs = System.currentTimeMillis();
        long lastSentAtMs = sentryThrottle.containsKey(dedupeKey)
                ? sentryThrottle.get(dedupeKey)
                : 0L;
        if (!shouldEmitNow(lastSentAtMs, nowMs, DEFAULT_SENTRY_THROTTLE_MS)) {
            return;
        }
        sentryThrottle.put(dedupeKey, nowMs);

        Sentry.captureEvent(createEvent(level, eventName, renderedMessage, throwable, tags, detailedTracingEnabled));
    }

    static SentryEvent createEvent(SentryLevel level, String eventName, String renderedMessage,
                                  Throwable throwable, Map<String, String> tags, boolean detailedTracingEnabled) {
        SentryEvent event = throwable == null ? new SentryEvent() : new SentryEvent(throwable);
        Message message = new Message();
        message.setFormatted(renderedMessage);
        event.setMessage(message);
        event.setLevel(level);
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                event.setTag(entry.getKey(), entry.getValue());
            }
        }
        // Put diagnostic fields on the event itself so they survive SDK scope/thread changes.
        event.setTag("logger_event", eventName);
        event.setTag("detailed_tracing_enabled", Boolean.toString(detailedTracingEnabled));
        return event;
    }
}
