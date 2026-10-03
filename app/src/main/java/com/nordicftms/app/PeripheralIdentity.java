package com.nordicftms.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;

/** Version 1 application identity, independent of Android's rotating BLE address. */
final class PeripheralIdentity {
    static final UUID SERVICE_UUID = UUID.fromString("2d3f96c1-45e8-4ebd-aad2-30d98c02d2b3");
    static final UUID CHARACTERISTIC_UUID = UUID.fromString("d219c59d-5319-42bd-b65d-1a97bd15df83");
    // 16-byte service UUID + 12-byte ID + AD type/length = 30 of 31 scan-response bytes.
    static final int ID_LENGTH = 12;
    private static final String FILE_NAME = "peripheral-identity-v1";

    private PeripheralIdentity() { }

    // The caller supplies Context.getNoBackupFilesDir(): copying an Android backup
    // to another console must not clone this identity.
    static synchronized byte[] loadOrCreate(File directory) throws IOException {
        if (directory == null) throw new IOException("Identity storage is unavailable");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create identity storage");
        }
        File destination = new File(directory, FILE_NAME);
        if (destination.exists()) {
            byte[] identity = new byte[ID_LENGTH];
            try (FileInputStream input = new FileInputStream(destination)) {
                int offset = 0;
                while (offset < identity.length) {
                    int count = input.read(identity, offset, identity.length - offset);
                    if (count < 0) throw new IOException("Stored peripheral identity is truncated");
                    offset += count;
                }
                if (input.read() != -1) throw new IOException("Stored peripheral identity has an invalid length");
            }
            return identity;
        }

        byte[] identity = new byte[ID_LENGTH];
        new SecureRandom().nextBytes(identity);
        File pending = new File(directory, FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(pending)) {
            output.write(identity);
            output.getFD().sync();
        }
        if (!pending.renameTo(destination)) {
            throw new IOException("Cannot persist peripheral identity");
        }
        return identity;
    }
}
