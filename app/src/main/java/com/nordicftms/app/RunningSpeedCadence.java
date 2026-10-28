package com.nordicftms.app;

import java.util.UUID;

/** RSC 1.0.1 encoding and sensor distance, independent of FTMS workout counters. */
final class RunningSpeedCadence {
    static final UUID SERVICE_UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb");
    static final UUID MEASUREMENT_UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb");
    static final UUID FEATURE_UUID = UUID.fromString("00002a54-0000-1000-8000-00805f9b34fb");
    static final UUID CONTROL_POINT_UUID = UUID.fromString("00002a55-0000-1000-8000-00805f9b34fb");
    private static final long MAX_DISTANCE = 0xffffffffL;

    private long totalDistanceDecimeters;
    private long lastConsoleDistanceDecimeters;
    private boolean hasConsoleDistance;

    static byte[] featureValue() {
        // Total distance only. No stride length, gait detection, or calibration.
        return new byte[]{0x02, 0x00};
    }

    synchronized void updateDistance(double consoleDistanceKm) {
        if (Double.isNaN(consoleDistanceKm) || Double.isInfinite(consoleDistanceKm)
                || consoleDistanceKm < 0) {
            return;
        }
        long distance = Math.round(Math.min(consoleDistanceKm * 10000.0, MAX_DISTANCE));
        // GlassOS resets its workout counter between workouts; an RSC odometer
        // must not decrease unless a client uses Set Cumulative Value.
        long delta = !hasConsoleDistance || distance < lastConsoleDistanceDecimeters
                ? distance : distance - lastConsoleDistanceDecimeters;
        totalDistanceDecimeters = Math.min(MAX_DISTANCE, totalDistanceDecimeters + delta);
        lastConsoleDistanceDecimeters = distance;
        hasConsoleDistance = true;
    }

    synchronized byte[] measurement(double speedKph) {
        double safeSpeed = Double.isNaN(speedKph) ? 0 : Math.max(0, speedKph);
        int speed = (int) Math.round(Math.min(0xffff, safeSpeed / 3.6 * 256.0));
        byte[] value = new byte[8];
        value[0] = 0x02; // Total Distance Present; walking/running status unsupported.
        value[1] = (byte) speed;
        value[2] = (byte) (speed >>> 8);
        // Cadence is mandatory. GlassOS has no treadmill footfall measurement;
        // leave it at zero rather than inventing cadence from belt speed.
        for (int i = 0; i < 4; i++) {
            value[4 + i] = (byte) (totalDistanceDecimeters >>> (8 * i));
        }
        return value;
    }

    synchronized byte[] controlPointResponse(byte[] request, double consoleDistanceKm) {
        int opcode = request == null || request.length == 0 ? 0 : request[0] & 0xff;
        int result;
        if (opcode != 0x01) {
            result = 0x02; // Op Code Not Supported
        } else if (request.length != 5) {
            result = 0x03; // Invalid Parameter
        } else if (Double.isNaN(consoleDistanceKm) || Double.isInfinite(consoleDistanceKm)
                || consoleDistanceKm < 0) {
            result = 0x04; // Operation Failed: cannot establish a console baseline yet.
        } else {
            updateDistance(consoleDistanceKm);
            long distance = 0;
            for (int i = 0; i < 4; i++) {
                distance |= (request[1 + i] & 0xffL) << (8 * i);
            }
            totalDistanceDecimeters = distance;
            result = 0x01; // Success; never changes GlassOS or FTMS distance.
        }
        return new byte[]{0x10, (byte) opcode, (byte) result};
    }
}
