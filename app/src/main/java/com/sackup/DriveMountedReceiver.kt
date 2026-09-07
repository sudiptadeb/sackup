package com.sackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sackup.util.DriveVolumes

/**
 * Manifest-declared receiver for MEDIA_MOUNTED (exempt from the implicit-broadcast limits, so it
 * fires even when the app is not running). When the saved USB drive mounts and the on-connect
 * setting is not "Do nothing", it posts a "USB drive connected" notification that opens
 * [MainActivity] with [MainActivity.EXTRA_DRIVE_CONNECTED].
 *
 * It never starts the backup service itself: Android 12+ forbids foreground-service starts from
 * the background. While the app is in the foreground, MainActivity's own runtime receiver handles
 * the mount, so nothing is posted then.
 */
class DriveMountedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DriveMountedReceiver"
        private const val PREFS = "sackup"
        private const val PREF_DRIVE_URI = "drive_uri"
        const val CHANNEL_ID = "sackup_connect"
        const val NOTIFICATION_ID = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        if (MainActivity.isInForeground) {
            Log.d(TAG, "App is in the foreground; the activity handles the mount")
            return
        }
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_DRIVE_URI, null) ?: return
        if (ConnectPrefs.get(context) == OnConnectMode.OFF) return
        val treeUri = runCatching { Uri.parse(saved) }.getOrNull() ?: return
        // volumeIdOf may be null for some providers; eventConcernsDrive then errs on "yes".
        if (!DriveVolumes.eventConcernsDrive(intent.data, treeUri)) {
            Log.d(TAG, "Mounted ${intent.data} is not the saved drive")
            return
        }
        postNotification(context)
    }

    private fun postNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB drive connected", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Offers to back up when the USB drive is plugged in" }
        )
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_DRIVE_CONNECTED, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("USB drive connected")
            .setContentText("Tap to back up your phone")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+).
            Log.w(TAG, "Could not post the drive-connected notification", e)
        }
    }
}
