package com.nordicftms.app;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Serializes asynchronous Android GATT service registration.
 *
 * <p>{@code BluetoothGattServer} supports only one pending {@code addService}
 * request. Callers must wait for {@code onServiceAdded} before submitting the
 * next service. A generation token also prevents callbacks from a closed GATT
 * server from completing a newer startup attempt.
 */
final class GattServiceRegistrationCoordinator {
    enum CompletionResult {
        STALE,
        UNEXPECTED,
        MISMATCH,
        FAILED,
        NEXT,
        COMPLETE
    }

    private final ArrayDeque<UUID> pendingServiceUuids = new ArrayDeque<>();
    private long generation = 0L;
    private UUID inFlightServiceUuid;

    synchronized long begin(List<UUID> serviceUuids) {
        generation++;
        pendingServiceUuids.clear();
        inFlightServiceUuid = null;

        Set<UUID> uniqueUuids = new HashSet<>();
        for (UUID serviceUuid : serviceUuids) {
            if (serviceUuid == null) {
                throw new IllegalArgumentException("GATT service UUID must not be null");
            }
            if (!uniqueUuids.add(serviceUuid)) {
                throw new IllegalArgumentException("Duplicate GATT service UUID: " + serviceUuid);
            }
            pendingServiceUuids.addLast(serviceUuid);
        }
        return generation;
    }

    synchronized void invalidate() {
        generation++;
        pendingServiceUuids.clear();
        inFlightServiceUuid = null;
    }

    synchronized boolean isCurrent(long callbackGeneration) {
        return callbackGeneration == generation;
    }

    synchronized UUID claimNext(long callbackGeneration) {
        if (callbackGeneration != generation || inFlightServiceUuid != null) {
            return null;
        }
        inFlightServiceUuid = pendingServiceUuids.pollFirst();
        return inFlightServiceUuid;
    }

    synchronized UUID getInFlightServiceUuid(long callbackGeneration) {
        return callbackGeneration == generation ? inFlightServiceUuid : null;
    }

    synchronized CompletionResult complete(
            long callbackGeneration,
            UUID serviceUuid,
            boolean successful
    ) {
        if (callbackGeneration != generation) {
            return CompletionResult.STALE;
        }
        if (inFlightServiceUuid == null) {
            return CompletionResult.UNEXPECTED;
        }
        if (!Objects.equals(inFlightServiceUuid, serviceUuid)) {
            return CompletionResult.MISMATCH;
        }

        inFlightServiceUuid = null;
        if (!successful) {
            pendingServiceUuids.clear();
            return CompletionResult.FAILED;
        }
        return pendingServiceUuids.isEmpty()
                ? CompletionResult.COMPLETE
                : CompletionResult.NEXT;
    }
}
