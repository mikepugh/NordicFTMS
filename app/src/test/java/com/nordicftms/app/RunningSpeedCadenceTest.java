package com.nordicftms.app;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RunningSpeedCadenceTest {
    @Test
    public void encodesSpeedAndDistanceInRscUnitsWithNoFabricatedCadence() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.updateDistance(1.2345);

        // 10.8 km/h = 3 m/s = 768 units; 1.2345 km = 12345 decimeters.
        assertArrayEquals(new byte[]{0x02, 0x00, 0x03, 0x00, 0x39, 0x30, 0x00, 0x00},
                sensor.measurement(10.8));
        assertArrayEquals(new byte[]{0x02, 0x00}, RunningSpeedCadence.featureValue());
    }

    @Test
    public void roundsFractionalSpeedAndDistance() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.updateDistance(0.00016);
        assertArrayEquals(new byte[]{0x02, (byte) 0xc7, 0x02, 0, 2, 0, 0, 0},
                sensor.measurement(10));
    }

    @Test
    public void reportsZeroAtRestAndClampsInvalidSpeed() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        for (double speed : new double[]{0, -1, Double.NaN, Double.NEGATIVE_INFINITY}) {
            assertArrayEquals(new byte[]{0x02, 0, 0, 0, 0, 0, 0, 0}, sensor.measurement(speed));
        }
        assertArrayEquals(new byte[]{0x02, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0},
                sensor.measurement(Double.POSITIVE_INFINITY));
    }

    @Test
    public void continuesAccumulatingWhenGlassOsStartsANewWorkout() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.updateDistance(1);
        sensor.updateDistance(1.2);
        sensor.updateDistance(0);
        sensor.updateDistance(0.1);
        sensor.updateDistance(0.1); // Sending to another client must not double count.
        assertEquals(13000, distance(sensor));
    }

    @Test
    public void setCumulativeValueDoesNotReAddExistingConsoleDistance() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        assertArrayEquals(new byte[]{0x10, 0x01, 0x01},
                sensor.controlPointResponse(new byte[]{1, 0, 0, 0, 0}, 2.5));
        sensor.updateDistance(2.5);
        assertEquals(0, distance(sensor));
        sensor.updateDistance(2.501);
        assertEquals(10, distance(sensor));

        assertArrayEquals(new byte[]{0x10, 0x01, 0x01},
                sensor.controlPointResponse(new byte[]{1, 0x78, 0x56, 0x34, 0x12}, 2.501));
        assertEquals(0x12345678L, distance(sensor));
        sensor.updateDistance(2.502);
        assertEquals(0x12345682L, distance(sensor));
    }

    @Test
    public void rejectsUnsupportedAndMalformedCommandsWithoutChangingDistance() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.updateDistance(1);
        assertArrayEquals(new byte[]{0x10, 2, 2}, sensor.controlPointResponse(new byte[]{2}, 1));
        assertArrayEquals(new byte[]{0x10, 1, 3}, sensor.controlPointResponse(new byte[]{1}, 1));
        assertArrayEquals(new byte[]{0x10, 1, 3},
                sensor.controlPointResponse(new byte[]{1, 0, 0, 0, 0, 0}, 1));
        assertArrayEquals(new byte[]{0x10, 0, 2}, sensor.controlPointResponse(null, 1));
        assertEquals(10000, distance(sensor));
    }

    @Test
    public void distanceUsesUnsigned32BitsAndSaturatesInsteadOfRollingOver() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.controlPointResponse(new byte[]{1, (byte) 0xfe, (byte) 0xff, (byte) 0xff, (byte) 0xff}, 0);
        assertEquals(0xfffffffeL, distance(sensor));
        sensor.updateDistance(0.1);
        assertEquals(0xffffffffL, distance(sensor));
        sensor.updateDistance(1);
        assertEquals(0xffffffffL, distance(sensor));
    }

    @Test
    public void unavailableConsoleDistanceDoesNotResetOrReAddDistance() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.updateDistance(2.5);
        assertArrayEquals(new byte[]{0x10, 1, 4},
                sensor.controlPointResponse(new byte[]{1, 0, 0, 0, 0}, Double.NaN));
        sensor.updateDistance(Double.NaN); // Backend reconnect has no current sample yet.
        sensor.updateDistance(2.5);
        assertEquals(25000, distance(sensor));
    }

    @Test
    public void invalidDistanceSamplesDoNotCorruptTheCounterOrBaseline() {
        RunningSpeedCadence sensor = new RunningSpeedCadence();
        sensor.updateDistance(1);
        sensor.updateDistance(Double.NaN);
        sensor.updateDistance(Double.POSITIVE_INFINITY);
        sensor.updateDistance(-1);
        sensor.updateDistance(1.1);
        assertEquals(11000, distance(sensor));
    }

    private long distance(RunningSpeedCadence sensor) {
        byte[] value = sensor.measurement(0);
        return (value[4] & 0xffL) | (value[5] & 0xffL) << 8
                | (value[6] & 0xffL) << 16 | (value[7] & 0xffL) << 24;
    }
}
