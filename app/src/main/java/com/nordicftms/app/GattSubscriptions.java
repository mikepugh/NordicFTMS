package com.nordicftms.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Connection-local CCCD values, scoped to each client and characteristic. */
final class GattSubscriptions<D> {
    static final int DISABLED = 0;
    static final int NOTIFICATIONS = 1;
    static final int INDICATIONS = 2;

    private final Map<D, Map<UUID, Integer>> clients = new HashMap<>();

    synchronized int get(D device, UUID characteristic) {
        Map<UUID, Integer> values = clients.get(device);
        Integer value = values == null ? null : values.get(characteristic);
        return value == null ? DISABLED : value;
    }

    synchronized void set(D device, UUID characteristic, int value) {
        if (value == DISABLED) {
            Map<UUID, Integer> values = clients.get(device);
            if (values != null) {
                values.remove(characteristic);
                if (values.isEmpty()) clients.remove(device);
            }
        } else {
            Map<UUID, Integer> values = clients.get(device);
            if (values == null) {
                values = new HashMap<>();
                clients.put(device, values);
            }
            values.put(characteristic, value);
        }
    }

    synchronized List<D> devicesFor(UUID characteristic, int configuration) {
        List<D> devices = new ArrayList<>();
        for (Map.Entry<D, Map<UUID, Integer>> client : clients.entrySet()) {
            Integer value = client.getValue().get(characteristic);
            if (value != null && value == configuration) {
                devices.add(client.getKey());
            }
        }
        return devices;
    }

    synchronized void remove(D device) {
        clients.remove(device);
    }

    synchronized void clear() {
        clients.clear();
    }
}
