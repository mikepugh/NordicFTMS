package com.nordicftms.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DirconServerProfileSelectionTest {
    @Test
    public void usesGenericServiceNameWhenKickrModeIsDisabled() {
        assertEquals("NordicFTMS", DirconServer.resolveDirconServiceName("AA-BB-CC-DD-EE-FF", false));
        assertEquals("0x1826", DirconServer.resolveDirconBleServiceUuids(false));
    }

    @Test
    public void buildsKickrRunServiceNameFromMacSuffix() {
        assertEquals("KICKR RUN EEFF", DirconServer.resolveDirconServiceName("AA-BB-CC-DD-EE-FF", true));
        assertEquals(
                "0x1826,0x1814,A026EE0E-0A7D-4AB3-97FA-F1500F9FEB8B",
                DirconServer.resolveDirconBleServiceUuids(true)
        );
    }
}
