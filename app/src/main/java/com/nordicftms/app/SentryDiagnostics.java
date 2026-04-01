package com.nordicftms.app;

import com.ifit.glassos.ConsoleInfo;
import com.ifit.glassos.ConsoleType;

import io.sentry.Sentry;
import io.sentry.SentryLevel;

/**
 * Small, low-volume Sentry helpers for field diagnostics.
 *
 * We intentionally log only a few key lifecycle decisions so the SDK stays
 * lightweight on older treadmill tablets.
 */
public final class SentryDiagnostics {
    private SentryDiagnostics() {
    }

    public static void recordConsoleInfo(
            ConsoleInfo consoleInfo,
            ConsoleType machineType,
            String source,
            boolean usable,
            int attempt
    ) {
        if (consoleInfo == null) {
            return;
        }

        String machineTypeName = describeMachineType(machineType);
        boolean speedAndInclineCapable = isSpeedAndInclineCapable(consoleInfo);

        Sentry.setTag("machine_type", machineTypeName);
        Sentry.setTag("console_can_set_incline", Boolean.toString(consoleInfo.getCanSetIncline()));
        Sentry.setTag("console_can_set_speed", Boolean.toString(consoleInfo.getCanSetSpeed()));
        Sentry.setTag("console_info_source", emptyToUnknown(source));
        Sentry.setTag("console_info_usable", Boolean.toString(usable));

        if (!consoleInfo.getName().isEmpty()) {
            Sentry.setTag("console_name", consoleInfo.getName());
        }

        Sentry.logger().info(
                "Console info fetched: source=%s attempt=%s usable=%s machineType=%s name=%s minKph=%.2f maxKph=%.2f minIncline=%.2f maxIncline=%.2f canSetSpeed=%s canSetIncline=%s canSetResistance=%s firmware=%s",
                emptyToUnknown(source),
                attempt > 0 ? Integer.toString(attempt) : "stream",
                usable,
                machineTypeName,
                emptyToUnknown(consoleInfo.getName()),
                consoleInfo.getMinKph(),
                consoleInfo.getMaxKph(),
                consoleInfo.getMinInclinePercent(),
                consoleInfo.getMaxInclinePercent(),
                consoleInfo.getCanSetSpeed(),
                consoleInfo.getCanSetIncline(),
                consoleInfo.getCanSetResistance(),
                emptyToUnknown(consoleInfo.getFirmwareVersion())
        );

        if (!usable) {
            Sentry.logger().warn(
                    "Console info payload looked empty: source=%s attempt=%s machineType=%s name=%s",
                    emptyToUnknown(source),
                    attempt > 0 ? Integer.toString(attempt) : "stream",
                    machineTypeName,
                    emptyToUnknown(consoleInfo.getName())
            );
        }

        if (speedAndInclineCapable && !isTreadmillMachineType(machineType)) {
            Sentry.logger().warn(
                    "Suspicious console classification: machineType=%s name=%s canSetSpeed=%s canSetIncline=%s maxKph=%.2f maxIncline=%.2f",
                    machineTypeName,
                    emptyToUnknown(consoleInfo.getName()),
                    consoleInfo.getCanSetSpeed(),
                    consoleInfo.getCanSetIncline(),
                    consoleInfo.getMaxKph(),
                    consoleInfo.getMaxInclinePercent()
            );
        }
    }

    public static void recordConsoleInfoFailure(String source, Throwable error) {
        Sentry.logger().error(
                "Failed to fetch console info from GlassOS via %s",
                emptyToUnknown(source)
        );

        if (error != null) {
            Sentry.captureException(error);
        }
    }

    public static void recordMissingBluetoothPermissions(
            String startupPhase,
            String bootSource,
            String missingPermissions,
            boolean bleExposed,
            boolean dirconExposed
    ) {
        Sentry.withScope(scope -> {
            scope.setLevel(SentryLevel.WARNING);
            scope.setTag("startup_phase", emptyToUnknown(startupPhase));
            scope.setTag("boot_source", emptyToUnknown(bootSource));
            scope.setTag("missing_permissions", emptyToUnknown(missingPermissions));
            scope.setTag("ble_exposed", Boolean.toString(bleExposed));
            scope.setTag("dircon_exposed", Boolean.toString(dirconExposed));
            Sentry.captureMessage("Bluetooth runtime permissions missing", SentryLevel.WARNING);
        });
    }

    public static void recordGrpcBackendUnavailable(
            String startupPhase,
            String source,
            Throwable error,
            int attempt,
            boolean bleExposed,
            boolean dirconExposed
    ) {
        Sentry.withScope(scope -> {
            scope.setLevel(SentryLevel.WARNING);
            scope.setTag("startup_phase", emptyToUnknown(startupPhase));
            scope.setTag("grpc_source", emptyToUnknown(source));
            scope.setTag("grpc_attempt", Integer.toString(Math.max(0, attempt)));
            scope.setTag("ble_exposed", Boolean.toString(bleExposed));
            scope.setTag("dircon_exposed", Boolean.toString(dirconExposed));
            if (error != null) {
                Sentry.captureException(error);
            } else {
                Sentry.captureMessage("GlassOS backend unavailable", SentryLevel.WARNING);
            }
        });
    }

    public static void recordDirconProfileSelection(
            GrpcControlService grpc,
            String serviceName,
            String bleServiceUuids,
            boolean treadmillProfile
    ) {
        ConsoleInfo consoleInfo = grpc != null ? grpc.getConsoleInfo() : null;
        ConsoleType machineType = grpc != null ? grpc.getMachineType() : ConsoleType.CONSOLE_TYPE_UNKNOWN;
        String machineTypeName = describeMachineType(machineType);

        Sentry.setTag("dircon_profile", treadmillProfile ? "treadmill" : "generic");
        Sentry.setTag("dircon_service_name", emptyToUnknown(serviceName));

        String consoleName = consoleInfo != null ? emptyToUnknown(consoleInfo.getName()) : "unknown";
        boolean canSetSpeed = consoleInfo != null && consoleInfo.getCanSetSpeed();
        boolean canSetIncline = consoleInfo != null && consoleInfo.getCanSetIncline();
        double maxKph = consoleInfo != null ? consoleInfo.getMaxKph() : 0.0;
        double maxIncline = consoleInfo != null ? consoleInfo.getMaxInclinePercent() : 0.0;

        Sentry.logger().info(
                "DIRCON profile selected: profile=%s serviceName=%s bleServiceUuids=%s machineType=%s consoleName=%s canSetSpeed=%s canSetIncline=%s maxKph=%.2f maxIncline=%.2f",
                treadmillProfile ? "treadmill" : "generic",
                emptyToUnknown(serviceName),
                emptyToUnknown(bleServiceUuids),
                machineTypeName,
                consoleName,
                canSetSpeed,
                canSetIncline,
                maxKph,
                maxIncline
        );

        if (!treadmillProfile && consoleInfo != null && isSpeedAndInclineCapable(consoleInfo)) {
            Sentry.logger().warn(
                    "DIRCON selected generic profile for speed/incline-capable console: machineType=%s name=%s maxKph=%.2f maxIncline=%.2f",
                    machineTypeName,
                    consoleName,
                    maxKph,
                    maxIncline
            );
        }
    }

    private static boolean isSpeedAndInclineCapable(ConsoleInfo consoleInfo) {
        return (consoleInfo.getCanSetSpeed() || consoleInfo.getMaxKph() > 0.0)
                && (consoleInfo.getCanSetIncline() || consoleInfo.getMaxInclinePercent() > 0.0);
    }

    private static boolean isTreadmillMachineType(ConsoleType machineType) {
        return machineType == ConsoleType.TREADMILL
                || machineType == ConsoleType.INCLINE_TRAINER;
    }

    private static String describeMachineType(ConsoleType machineType) {
        return machineType != null ? machineType.name() : ConsoleType.CONSOLE_TYPE_UNKNOWN.name();
    }

    private static String emptyToUnknown(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
