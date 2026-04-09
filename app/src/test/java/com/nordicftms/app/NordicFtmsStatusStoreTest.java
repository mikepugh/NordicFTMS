package com.nordicftms.app;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NordicFtmsStatusStoreTest {
    private final NordicFtmsStatusStore statusStore = NordicFtmsStatusStore.getInstance();

    @After
    public void tearDown() {
        statusStore.reset();
    }

    @Test
    public void updatePublishesSnapshotChangesToListeners() {
        AtomicReference<ServiceStatusSnapshot> latestSnapshot = new AtomicReference<>();
        NordicFtmsStatusStore.Listener listener = latestSnapshot::set;

        statusStore.reset();
        statusStore.addListener(listener);
        statusStore.update(snapshot -> {
            snapshot.backendState = ServiceStatusSnapshot.BackendState.RETRYING;
            snapshot.reconnectAttempt = 3;
        });

        assertEquals(ServiceStatusSnapshot.BackendState.RETRYING, latestSnapshot.get().backendState);
        assertEquals(3, latestSnapshot.get().reconnectAttempt);

        statusStore.removeListener(listener);
    }

    @Test
    public void getSnapshotReturnsADefensiveCopy() {
        statusStore.reset();
        statusStore.update(snapshot -> snapshot.advertisedFtmsName = "Changed");

        ServiceStatusSnapshot firstSnapshot = statusStore.getSnapshot();
        firstSnapshot.advertisedFtmsName = "Mutated";
        ServiceStatusSnapshot secondSnapshot = statusStore.getSnapshot();

        assertEquals("Changed", secondSnapshot.advertisedFtmsName);
        assertTrue(secondSnapshot != firstSnapshot);
    }

    @Test
    public void clearLastErrorResetsStoredErrorState() {
        statusStore.reset();
        statusStore.update(snapshot -> {
            snapshot.lastError.level = "WARNING";
            snapshot.lastError.eventName = "speed_subscription_error";
            snapshot.lastError.message = "Speed subscription error";
            snapshot.lastError.timestampMs = 1234L;
        });

        statusStore.clearLastError();

        ServiceStatusSnapshot snapshot = statusStore.getSnapshot();
        assertEquals("", snapshot.lastError.level);
        assertEquals("", snapshot.lastError.eventName);
        assertEquals("", snapshot.lastError.message);
        assertEquals(0L, snapshot.lastError.timestampMs);
    }
}
