package com.nordicftms.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GattServiceRegistrationCoordinatorTest {
    private static final UUID DEVICE_INFO_UUID =
            UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB");
    private static final UUID FTMS_UUID =
            UUID.fromString("00001826-0000-1000-8000-00805F9B34FB");

    @Test
    public void registersServicesOneAtATime() {
        GattServiceRegistrationCoordinator coordinator =
                new GattServiceRegistrationCoordinator();
        long generation = coordinator.begin(Arrays.asList(DEVICE_INFO_UUID, FTMS_UUID));

        assertEquals(DEVICE_INFO_UUID, coordinator.claimNext(generation));
        assertNull(coordinator.claimNext(generation));
        assertEquals(
                GattServiceRegistrationCoordinator.CompletionResult.NEXT,
                coordinator.complete(generation, DEVICE_INFO_UUID, true)
        );

        assertEquals(FTMS_UUID, coordinator.claimNext(generation));
        assertEquals(
                GattServiceRegistrationCoordinator.CompletionResult.COMPLETE,
                coordinator.complete(generation, FTMS_UUID, true)
        );
    }

    @Test
    public void rejectsMismatchedServiceCallbackWithoutAdvancing() {
        GattServiceRegistrationCoordinator coordinator =
                new GattServiceRegistrationCoordinator();
        long generation = coordinator.begin(Arrays.asList(DEVICE_INFO_UUID, FTMS_UUID));

        assertEquals(DEVICE_INFO_UUID, coordinator.claimNext(generation));
        assertEquals(
                GattServiceRegistrationCoordinator.CompletionResult.MISMATCH,
                coordinator.complete(generation, FTMS_UUID, true)
        );
        assertEquals(DEVICE_INFO_UUID, coordinator.getInFlightServiceUuid(generation));
    }

    @Test
    public void ignoresCallbacksFromInvalidatedGeneration() {
        GattServiceRegistrationCoordinator coordinator =
                new GattServiceRegistrationCoordinator();
        long staleGeneration = coordinator.begin(Collections.singletonList(FTMS_UUID));
        assertEquals(FTMS_UUID, coordinator.claimNext(staleGeneration));

        long currentGeneration = coordinator.begin(Collections.singletonList(FTMS_UUID));

        assertFalse(coordinator.isCurrent(staleGeneration));
        assertTrue(coordinator.isCurrent(currentGeneration));
        assertEquals(
                GattServiceRegistrationCoordinator.CompletionResult.STALE,
                coordinator.complete(staleGeneration, FTMS_UUID, true)
        );
        assertEquals(FTMS_UUID, coordinator.claimNext(currentGeneration));
    }

    @Test
    public void stopsSequenceAfterRegistrationFailure() {
        GattServiceRegistrationCoordinator coordinator =
                new GattServiceRegistrationCoordinator();
        long generation = coordinator.begin(Arrays.asList(DEVICE_INFO_UUID, FTMS_UUID));

        assertEquals(DEVICE_INFO_UUID, coordinator.claimNext(generation));
        assertEquals(
                GattServiceRegistrationCoordinator.CompletionResult.FAILED,
                coordinator.complete(generation, DEVICE_INFO_UUID, false)
        );
        assertNull(coordinator.claimNext(generation));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateServiceUuids() {
        GattServiceRegistrationCoordinator coordinator =
                new GattServiceRegistrationCoordinator();
        coordinator.begin(Arrays.asList(FTMS_UUID, FTMS_UUID));
    }
}
