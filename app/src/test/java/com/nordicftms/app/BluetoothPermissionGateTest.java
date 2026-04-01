package com.nordicftms.app;

import android.Manifest;
import android.os.Build;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class BluetoothPermissionGateTest {
    @Test
    public void doesNotRequireRuntimeBluetoothPermissionsBeforeAndroid12() {
        assertArrayEquals(
                new String[0],
                BluetoothPermissionGate.getRequiredPermissionsForSdk(Build.VERSION_CODES.R)
        );
    }

    @Test
    public void requiresConnectAndAdvertisePermissionsOnAndroid12Plus() {
        assertArrayEquals(
                new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                },
                BluetoothPermissionGate.getRequiredPermissionsForSdk(Build.VERSION_CODES.S)
        );
    }

    @Test
    public void formatsMissingPermissionsForStableSentryTags() {
        assertEquals(
                "BLUETOOTH_CONNECT,BLUETOOTH_ADVERTISE",
                BluetoothPermissionGate.describeMissingPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                })
        );
    }
}
