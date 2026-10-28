package com.nordicftms.app;

import org.junit.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GattSubscriptionsTest {
    private static final UUID TREADMILL = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb");

    @Test
    public void ftmsAndRscClientsSubscribeIndependently() {
        GattSubscriptions<String> subscriptions = new GattSubscriptions<>();
        subscriptions.set("phone", TREADMILL, GattSubscriptions.NOTIFICATIONS);
        subscriptions.set("watch", RunningSpeedCadence.MEASUREMENT_UUID, GattSubscriptions.NOTIFICATIONS);
        subscriptions.set("watch", RunningSpeedCadence.CONTROL_POINT_UUID, GattSubscriptions.INDICATIONS);

        assertEquals(Collections.singletonList("phone"),
                subscriptions.devicesFor(TREADMILL, GattSubscriptions.NOTIFICATIONS));
        assertEquals(Collections.singletonList("watch"),
                subscriptions.devicesFor(RunningSpeedCadence.MEASUREMENT_UUID, GattSubscriptions.NOTIFICATIONS));
        assertEquals(GattSubscriptions.DISABLED, subscriptions.get("watch", TREADMILL));
        assertEquals(GattSubscriptions.INDICATIONS,
                subscriptions.get("watch", RunningSpeedCadence.CONTROL_POINT_UUID));
    }

    @Test
    public void disablingOneCharacteristicKeepsOtherSubscriptions() {
        GattSubscriptions<String> subscriptions = new GattSubscriptions<>();
        subscriptions.set("phone", TREADMILL, GattSubscriptions.NOTIFICATIONS);
        subscriptions.set("phone", RunningSpeedCadence.MEASUREMENT_UUID, GattSubscriptions.NOTIFICATIONS);
        subscriptions.set("phone", TREADMILL, GattSubscriptions.DISABLED);

        assertTrue(subscriptions.devicesFor(TREADMILL, GattSubscriptions.NOTIFICATIONS).isEmpty());
        assertEquals(GattSubscriptions.NOTIFICATIONS,
                subscriptions.get("phone", RunningSpeedCadence.MEASUREMENT_UUID));
    }

    @Test
    public void disconnectAndRestartClearOnlyTheExpectedState() {
        GattSubscriptions<String> subscriptions = new GattSubscriptions<>();
        subscriptions.set("phone", TREADMILL, GattSubscriptions.NOTIFICATIONS);
        subscriptions.set("watch", RunningSpeedCadence.CONTROL_POINT_UUID, GattSubscriptions.INDICATIONS);
        subscriptions.remove("watch");
        assertEquals(GattSubscriptions.DISABLED,
                subscriptions.get("watch", RunningSpeedCadence.CONTROL_POINT_UUID));
        assertEquals(GattSubscriptions.NOTIFICATIONS, subscriptions.get("phone", TREADMILL));
        subscriptions.clear();
        assertEquals(GattSubscriptions.DISABLED, subscriptions.get("phone", TREADMILL));
    }
}
