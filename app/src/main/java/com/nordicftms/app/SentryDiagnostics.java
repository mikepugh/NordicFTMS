package com.nordicftms.app;

import com.ifit.glassos.ConsoleInfo;
import com.ifit.glassos.ConsoleType;

import io.sentry.Sentry;

/**
 * Small, low-volume Sentry helpers for field diagnostics.
 *
 * We intentionally log only a few key lifecycle decisions so the SDK stays
 * lightweight on older treadmill tablets.
 */
public final class SentryDiagnostics {
    private SentryDiagnostics() {
    }

    public static void recordConsoleInfo(ConsoleInfo consoleInfo, ConsoleType machineType) {
        if (consoleInfo == null) {
            return;
        }

        String machineTypeName = describeMachineType(machineType);
        boolean speedAndInclineCapable = isSpeedAndInclineCapable(consoleInfo);

        Sentry.setTag("machine_type", machineTypeName);
        Sentry.setTag("console_can_set_incline", Boolean.toString(consoleInfo.getCanSetIncline()));
        Sentry.setTag("console_can_set_speed", Boolean.toString(consoleInfo.getCanSetSpeed()));

        if (!consoleInfo.getName().isEmpty()) {
            Sentry.setTag("console_name", consoleInfo.getName());
        }

        Sentry.logger().info(
                "Console info fetched: machineType=%s name=%s minKph=%.2f maxKph=%.2f minIncline=%.2f maxIncline=%.2f canSetSpeed=%s canSetIncline=%s canSetResistance=%s firmware=%s",
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

    public static void recordConsoleInfoFailure(Throwable error) {
        Sentry.logger().error("Failed to fetch console info from GlassOS");

        if (error != null) {
            Sentry.captureException(error);
        }
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
