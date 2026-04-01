package com.nordicftms.app;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class BluetoothPermissionGate {
    private BluetoothPermissionGate() {
    }

    public static boolean requiresRuntimePermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static String[] getRequiredPermissions() {
        return getRequiredPermissionsForSdk(Build.VERSION.SDK_INT);
    }

    static String[] getRequiredPermissionsForSdk(int sdkInt) {
        if (sdkInt < Build.VERSION_CODES.S) {
            return new String[0];
        }

        return new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
        };
    }

    public static boolean hasRequiredPermissions(Context context) {
        return getMissingPermissions(context).length == 0;
    }

    public static String[] getMissingPermissions(Context context) {
        String[] requiredPermissions = getRequiredPermissions();
        if (requiredPermissions.length == 0) {
            return new String[0];
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        return missingPermissions.toArray(new String[0]);
    }

    public static String describeMissingPermissions(Context context) {
        return describeMissingPermissions(getMissingPermissions(context));
    }

    static String describeMissingPermissions(String[] permissions) {
        if (permissions == null || permissions.length == 0) {
            return "none";
        }

        StringBuilder description = new StringBuilder();
        for (int index = 0; index < permissions.length; index++) {
            if (index > 0) {
                description.append(",");
            }
            description.append(permissionToTagValue(permissions[index]));
        }
        return description.toString();
    }

    public static String permissionDisplayLabel(String permission) {
        return permissionToTagValue(permission).replace('_', ' ');
    }

    private static String permissionToTagValue(String permission) {
        if (permission == null || permission.isEmpty()) {
            return "unknown_permission";
        }

        int prefixIndex = permission.lastIndexOf('.');
        return prefixIndex >= 0 ? permission.substring(prefixIndex + 1) : permission;
    }
}
