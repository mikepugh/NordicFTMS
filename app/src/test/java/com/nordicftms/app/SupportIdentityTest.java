package com.nordicftms.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class SupportIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void survivesReloadAndDiffersBetweenInstallations() throws Exception {
        File first = temporary.newFolder();
        String id = SupportIdentity.loadOrCreate(first);
        assertTrue(id.matches("NFT-[0-9a-f]{20}"));
        assertEquals(id, SupportIdentity.loadOrCreate(first));
        assertNotEquals(id, SupportIdentity.loadOrCreate(temporary.newFolder()));
    }

    @Test(expected = IOException.class)
    public void corruptedIdentityIsNotSilentlyReplaced() throws Exception {
        File directory = temporary.newFolder();
        SupportIdentity.loadOrCreate(directory);
        try (FileOutputStream output = new FileOutputStream(new File(directory, "support-id-v1"))) {
            output.write(0);
        }
        SupportIdentity.loadOrCreate(directory);
    }
}
