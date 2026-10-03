package com.nordicftms.app;

import android.content.Context;
import android.os.SystemClock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.sentry.EventProcessor;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryId;
import io.sentry.util.HintUtils;

final class SupportDiagnostics {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final DiagnosticReportLimiter limiter = new DiagnosticReportLimiter();
    private static String supportId;
    private static boolean persistentId;
    private static volatile Map<String, Object> backend = Collections.emptyMap();

    private SupportDiagnostics() { }

    static synchronized void initialize(Context context) {
        if (supportId != null) return;
        try {
            supportId = SupportIdentity.loadOrCreate(context.getNoBackupFilesDir());
            persistentId = true;
        } catch (Exception error) {
            // Diagnostic storage must never prevent normal startup.
            supportId = SupportIdentity.create();
        }
        Context app = context.getApplicationContext();
        Sentry.getCurrentScopes().getOptions().addEventProcessor(new EventProcessor() {
            @Override
            public SentryEvent process(SentryEvent event, Hint hint) {
                // Preserve metadata from events cached by an earlier process.
                if (HintUtils.shouldApplyScopeData(hint) && event.getTag("support_id") == null) {
                    event.setTag("support_id", supportId);
                    event.setTag("support_id_persistent", Boolean.toString(persistentId));
                    event.setTag("detailed_tracing_enabled",
                            Boolean.toString(NordicFtmsPreferences.isDetailedTracingEnabled(app)));
                    event.getContexts().put("nordicftms_runtime",
                            DiagnosticReport.runtime(NordicFtmsStatusStore.getInstance().getSnapshot()));
                    event.getContexts().put("glassos_connection", backend);
                }
                return event;
            }
        });
    }

    static synchronized String getSupportId(Context context) {
        initialize(context);
        return supportId + (persistentId ? "" : " (temporary)");
    }

    static void recordBackend(Map<String, Object> snapshot) {
        backend = Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    static void request(Context context, String reason, boolean manual) {
        Context app = context.getApplicationContext();
        initialize(app);
        if (!manual && !NordicFtmsPreferences.isDetailedTracingEnabled(app)) return;
        if (!Sentry.isEnabled()) {
            updateStatus("Reporting unavailable in this build", false);
            return;
        }
        if (!limiter.acquire(reason, SystemClock.elapsedRealtime(), manual)) return;
        Map<String, Object> connection = backend;
        Map<String, Object> runtime = DiagnosticReport.runtime(NordicFtmsStatusStore.getInstance().getSnapshot());
        updateStatus("Collecting diagnostic report...", true);
        executor.execute(() -> {
            try {
                if (!manual && !NordicFtmsPreferences.isDetailedTracingEnabled(app)) {
                    updateStatus("Automatic diagnostics disabled", false);
                    return;
                }
                Map<String, Object> report = DiagnosticReport.collect(app);
                boolean detailedTracing = NordicFtmsPreferences.isDetailedTracingEnabled(app);
                if (!manual && !detailedTracing) {
                    updateStatus("Automatic diagnostics disabled", false);
                    return;
                }
                SentryEvent event = DiagnosticReport.createEvent(supportId, persistentId, reason,
                        detailedTracing, runtime, connection, report);
                SentryId id = Sentry.captureEvent(event);
                if (SentryId.EMPTY_ID.equals(id)) {
                    updateStatus("Report was not queued; try again", false);
                } else {
                    updateStatus("Report queued for delivery: " + id, false);
                }
            } catch (Exception error) {
                updateStatus("Could not collect report: " + error.getClass().getSimpleName(), false);
            } finally {
                limiter.release();
            }
        });
    }

    private static void updateStatus(String message, boolean collecting) {
        NordicFtmsStatusStore.getInstance().update(snapshot -> {
            snapshot.diagnosticReportStatus = message;
            snapshot.diagnosticReportInProgress = collecting;
        });
    }
}
