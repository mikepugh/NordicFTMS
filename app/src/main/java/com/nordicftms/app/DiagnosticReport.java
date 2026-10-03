package com.nordicftms.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;

final class DiagnosticReport {
    // Keep this allowlist in sync with <queries> in AndroidManifest.xml.
    static final String[] BACKEND_PACKAGES = {
            "com.ifit.glassos_service", "com.ifit.mithlond", "com.ifit.standalone",
            "com.ifit.eru", "com.ifit.launcher"
    };

    private DiagnosticReport() { }

    static Map<String, Object> collect(Context context) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("captured_at_ms", System.currentTimeMillis());
        report.put("uptime_ms", SystemClock.elapsedRealtime());
        TimeZone zone = TimeZone.getDefault();
        report.put("timezone", zone.getID());
        report.put("utc_offset_minutes", zone.getOffset(System.currentTimeMillis()) / 60_000);
        report.put("app_version", BuildConfig.VERSION_NAME);
        report.put("app_version_code", BuildConfig.VERSION_CODE);
        report.put("android_version", Build.VERSION.RELEASE);
        report.put("android_sdk", Build.VERSION.SDK_INT);
        report.put("android_build", Build.DISPLAY);
        report.put("manufacturer", Build.MANUFACTURER);
        report.put("model", Build.MODEL);
        report.put("hardware", Build.HARDWARE);
        report.put("board", Build.BOARD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            report.put("security_patch", Build.VERSION.SECURITY_PATCH);
        }

        Map<String, Object> packages = new LinkedHashMap<>();
        for (String name : BACKEND_PACKAGES) {
            packages.put(name, describePackage(context.getPackageManager(), name));
        }
        report.put("backend_packages", packages);
        // Android does not reliably expose other apps' running services. Package stopped=false
        // is not evidence that GlassOS is running; TCP and actual RPC results supply that evidence.
        report.put("service_process_state", "not_observable_by_this_app");
        report.put("tcp_ipv4", BackendPortProbe.probe("127.0.0.1", 54321, 1000));
        report.put("tcp_ipv6", BackendPortProbe.probe("::1", 54321, 1000));
        try {
            List<String> addresses = new ArrayList<>();
            for (InetAddress address : InetAddress.getAllByName("localhost")) {
                addresses.add(address.getHostAddress());
            }
            report.put("localhost_addresses", addresses);
        } catch (Exception error) {
            report.put("localhost_resolution_error", ConsoleDiscoveryDiagnostics.describeError(error));
        }
        return report;
    }

    static Map<String, Object> describePackage(PackageManager manager, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            PackageInfo info = manager.getPackageInfo(name, PackageManager.GET_SERVICES);
            result.put("availability", "installed");
            result.put("version_name", info.versionName == null ? "unknown" : info.versionName);
            result.put("version_code", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode);
            result.put("first_install_ms", info.firstInstallTime);
            result.put("last_update_ms", info.lastUpdateTime);
            if (info.applicationInfo != null) {
                result.put("enabled", info.applicationInfo.enabled);
                result.put("stopped_flag", (info.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0);
            }
            List<String> services = new ArrayList<>();
            if (info.services != null) for (ServiceInfo service : info.services) {
                services.add(service.name + " exported=" + service.exported + " enabled=" + service.enabled
                        + " permission=" + service.permission);
            }
            result.put("declared_services", services);
        } catch (PackageManager.NameNotFoundException error) {
            result.put("availability", "not_installed_or_not_visible");
        } catch (RuntimeException error) {
            result.put("availability", "query_failed");
            result.put("error", ConsoleDiscoveryDiagnostics.describeError(error));
        }
        return result;
    }

    static Map<String, Object> runtime(ServiceStatusSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service_state", snapshot.serviceState.name());
        result.put("backend_state", snapshot.backendState.name());
        result.put("backend_path", snapshot.backendPath);
        result.put("bluetooth_state", snapshot.bleState.name());
        result.put("bluetooth_permissions", snapshot.bluetoothPermissionState.name());
        result.put("dircon_profile", snapshot.dirconProfile.name());
        result.put("reconnect_attempt", snapshot.reconnectAttempt);
        result.put("detailed_tracing_enabled", snapshot.detailedTracingEnabled);
        result.put("kickr_run_mode", snapshot.kickrRunModeEnabled);
        result.put("last_error_event", snapshot.lastError.eventName);
        result.put("last_error_message", snapshot.lastError.message);
        result.put("last_error_at_ms", snapshot.lastError.timestampMs);
        result.put("console_name", snapshot.consoleSummary.machineName);
        result.put("machine_type", snapshot.consoleSummary.machineType);
        result.put("firmware", snapshot.consoleSummary.firmwareVersion);
        return result;
    }

    static SentryEvent createEvent(String supportId, boolean persistentId, String reason,
                                  boolean detailedTracing, Map<String, Object> runtime,
                                  Map<String, Object> connection, Map<String, Object> report) {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setFormatted("NordicFTMS diagnostic report");
        event.setMessage(message);
        event.setLevel(SentryLevel.INFO);
        event.setFingerprints(Collections.singletonList("nordicftms-diagnostic-report-v1"));
        event.setTag("diagnostic_reason", reason);
        event.setTag("logger_event", "diagnostic_report");
        event.setTag("support_id", supportId);
        event.setTag("support_id_persistent", Boolean.toString(persistentId));
        event.setTag("detailed_tracing_enabled", Boolean.toString(detailedTracing));
        event.getContexts().put("nordicftms_runtime", runtime);
        event.getContexts().put("glassos_connection", connection);
        event.getContexts().put("support_diagnostics", report);
        return event;
    }
}
