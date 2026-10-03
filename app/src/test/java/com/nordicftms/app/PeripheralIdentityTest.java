package com.nordicftms.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

public class PeripheralIdentityTest {
    @Rule public TemporaryFolder storage = new TemporaryFolder();

    @Test
    public void survivesReloadAndDoesNotShareMutableBytes() throws Exception {
        File directory = storage.newFolder();
        byte[] initial = PeripheralIdentity.loadOrCreate(directory);
        byte[] reloaded = PeripheralIdentity.loadOrCreate(directory);
        assertArrayEquals(initial, reloaded);
        initial[0] ^= 1;
        assertArrayEquals(reloaded, PeripheralIdentity.loadOrCreate(directory));
    }

    @Test
    public void separateInstallationsHaveIndependentIdentities() throws Exception {
        assertFalse(Arrays.equals(PeripheralIdentity.loadOrCreate(storage.newFolder()),
                PeripheralIdentity.loadOrCreate(storage.newFolder())));
    }

    @Test
    public void concurrentStartupUsesOnePersistedIdentity() throws Exception {
        File directory = storage.newFolder();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> first = executor.submit(() -> PeripheralIdentity.loadOrCreate(directory));
            Future<byte[]> second = executor.submit(() -> PeripheralIdentity.loadOrCreate(directory));
            assertArrayEquals(first.get(), second.get());
            assertArrayEquals(first.get(), PeripheralIdentity.loadOrCreate(directory));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(expected = IOException.class)
    public void storageFailureDoesNotReturnAnUnpersistedIdentity() throws Exception {
        PeripheralIdentity.loadOrCreate(storage.newFile());
    }

    @Test(expected = IOException.class)
    public void corruptedIdentityDoesNotSilentlyBecomeAnotherDevice() throws Exception {
        File directory = storage.newFolder();
        PeripheralIdentity.loadOrCreate(directory);
        try (FileOutputStream output = new FileOutputStream(new File(directory, "peripheral-identity-v1"))) {
            output.write(1);
        }
        PeripheralIdentity.loadOrCreate(directory);
    }
}
