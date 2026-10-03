package com.nordicftms.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

final class SupportIdentity {
    private SupportIdentity() { }

    static String create() {
        return "NFT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    static synchronized String loadOrCreate(File directory) throws IOException {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Support identity storage unavailable");
        }
        File file = new File(directory, "support-id-v1");
        if (file.exists()) {
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                String id = input.readUTF();
                if (!id.matches("NFT-[0-9a-f]{20}") || input.read() != -1) {
                    throw new IOException("Invalid support identity");
                }
                return id;
            }
        }
        String id = create();
        File pending = new File(directory, "support-id-v1.tmp");
        try (FileOutputStream stream = new FileOutputStream(pending);
             DataOutputStream output = new DataOutputStream(stream)) {
            output.writeUTF(id);
            output.flush();
            stream.getFD().sync();
        }
        if (!pending.renameTo(file)) throw new IOException("Cannot save support identity");
        return id;
    }
}
