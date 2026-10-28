package com.nordicftms.app;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class GattNotificationQueueTest {
    private static final UUID TREADMILL = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb");

    @Test
    public void waitsForMatchingCallbackBeforeSendingAnotherCharacteristic() {
        GattNotificationQueue<String> queue = new GattNotificationQueue<>();
        queue.enqueue("phone", TREADMILL, new byte[]{1}, false);
        queue.enqueue("watch", RunningSpeedCadence.MEASUREMENT_UUID, new byte[]{2}, false);
        assertEquals(TREADMILL, queue.claimNext().characteristic);
        assertNull(queue.claimNext());
        assertNull(queue.complete("watch"));
        assertNull(queue.claimNext());
        assertEquals("phone", queue.complete("phone").device);
        assertEquals(RunningSpeedCadence.MEASUREMENT_UUID, queue.claimNext().characteristic);
    }

    @Test
    public void snapshotsPayloadsAndCoalescesOnlyUnsentMeasurements() {
        GattNotificationQueue<String> queue = new GattNotificationQueue<>();
        byte[] value = new byte[]{1};
        queue.enqueue("watch", RunningSpeedCadence.MEASUREMENT_UUID, value, false);
        value[0] = 99;
        assertArrayEquals(new byte[]{1}, queue.claimNext().value);
        queue.enqueue("watch", RunningSpeedCadence.MEASUREMENT_UUID, new byte[]{2}, false);
        queue.enqueue("watch", RunningSpeedCadence.MEASUREMENT_UUID, new byte[]{3}, false);
        queue.complete("watch");
        assertArrayEquals(new byte[]{3}, queue.claimNext().value);
        queue.complete("watch");
        assertNull(queue.claimNext());
    }

    @Test
    public void preservesIndicationsAndMachineStatusEvents() {
        GattNotificationQueue<String> queue = new GattNotificationQueue<>();
        UUID status = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb");
        queue.enqueue("watch", RunningSpeedCadence.CONTROL_POINT_UUID, new byte[]{1}, true);
        queue.enqueue("watch", RunningSpeedCadence.CONTROL_POINT_UUID, new byte[]{2}, true);
        queue.enqueue("phone", status, new byte[]{3}, false);
        queue.enqueue("phone", status, new byte[]{4}, false);
        assertTrue(queue.claimNext().indicate);
        queue.complete("watch");
        assertArrayEquals(new byte[]{2}, queue.claimNext().value);
        queue.complete("watch");
        assertArrayEquals(new byte[]{3}, queue.claimNext().value);
        queue.complete("phone");
        assertArrayEquals(new byte[]{4}, queue.claimNext().value);
    }

    @Test
    public void disconnectClearsOnlyThatClientsQueuedAndInFlightValues() {
        GattNotificationQueue<String> queue = new GattNotificationQueue<>();
        queue.enqueue("watch", RunningSpeedCadence.MEASUREMENT_UUID, new byte[]{1}, false);
        queue.enqueue("watch", RunningSpeedCadence.CONTROL_POINT_UUID, new byte[]{2}, true);
        queue.enqueue("phone", TREADMILL, new byte[]{3}, false);
        queue.claimNext();
        assertTrue(queue.remove("watch"));
        assertEquals("phone", queue.claimNext().device);
        queue.clear();
        assertNull(queue.complete("phone"));
        assertNull(queue.claimNext());
    }
}
