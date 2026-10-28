package com.nordicftms.app;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;

/** Android requires waiting for onNotificationSent before sending the next value. */
final class GattNotificationQueue<D> {
    private static final UUID MACHINE_STATUS_UUID =
            UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb");
    static final class Notification<D> {
        final D device;
        final UUID characteristic;
        final byte[] value;
        final boolean indicate;

        Notification(D device, UUID characteristic, byte[] value, boolean indicate) {
            this.device = device;
            this.characteristic = characteristic;
            this.value = value.clone();
            this.indicate = indicate;
        }
    }

    private final ArrayDeque<Notification<D>> pending = new ArrayDeque<>();
    private Notification<D> inFlight;

    void enqueue(D device, UUID characteristic, byte[] value, boolean indicate) {
        // Drop superseded measurements if a client is slow. Control responses
        // and machine status events must retain their order.
        if (!indicate && !characteristic.equals(MACHINE_STATUS_UUID)) {
            Iterator<Notification<D>> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Notification<D> item = iterator.next();
                if (!item.indicate && item.device.equals(device)
                        && item.characteristic.equals(characteristic)) iterator.remove();
            }
        }
        pending.addLast(new Notification<>(device, characteristic, value, indicate));
    }

    Notification<D> claimNext() {
        if (inFlight != null) return null;
        inFlight = pending.pollFirst();
        return inFlight;
    }

    Notification<D> complete(D device) {
        if (inFlight == null || !inFlight.device.equals(device)) return null;
        Notification<D> result = inFlight;
        inFlight = null;
        return result;
    }

    boolean remove(D device) {
        Iterator<Notification<D>> iterator = pending.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().device.equals(device)) iterator.remove();
        }
        if (inFlight != null && inFlight.device.equals(device)) {
            inFlight = null;
            return true;
        }
        return false;
    }

    void clear() {
        pending.clear();
        inFlight = null;
    }
}
