package com.nordicftms.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Starts FTMSService automatically on device boot.
 * If Bluetooth runtime permissions are still missing, boot startup is skipped
 * until the user opens NordicFTMS and grants access.
 */
public class BootUpReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "NordicFTMS";
    private static final String REMINDER_CHANNEL_ID = "nordicftms_permission_reminder";
    private static final int REMINDER_NOTIFICATION_ID = 1339;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (!BluetoothPermissionGate.hasRequiredPermissions(context)) {
                String missingPermissions = BluetoothPermissionGate.describeMissingPermissions(context);
                Log.w(LOG_TAG, "Boot completed but Bluetooth permissions are missing: " + missingPermissions);
                SentryDiagnostics.recordMissingBluetoothPermissions(
                        "waiting_for_permission",
                        "boot_completed",
                        missingPermissions,
                        false,
                        false
                );
                maybeShowPermissionReminder(context);
                return;
            }

            Log.i(LOG_TAG, "Boot completed — starting FTMSService");
            Intent ftmsIntent = new Intent(context, FTMSService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(ftmsIntent);
            } else {
                context.startService(ftmsIntent);
            }
        }
    }

    private void maybeShowPermissionReminder(Context context) {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        if (!notificationManager.areNotificationsEnabled()) {
            Log.i(LOG_TAG, "Skipping boot permission reminder notification because notifications are unavailable");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "NordicFTMS Setup Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager systemNotificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (systemNotificationManager != null) {
                systemNotificationManager.createNotificationChannel(channel);
            }
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("NordicFTMS needs Bluetooth access")
                .setContentText("Open NordicFTMS once to finish granting Bluetooth permissions.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("NordicFTMS could not start automatically after boot because Android has not "
                                + "granted Bluetooth access yet. Open the app once to finish setup."))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        notificationManager.notify(REMINDER_NOTIFICATION_ID, notification);
    }
}
