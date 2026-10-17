package app.flowsync.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.flowsync.R
import app.flowsync.ui.MainActivity

object Notifications {
    const val CHANNEL_SYNC = "sync_progress"
    const val CHANNEL_RESULT = "sync_result"
    const val CHANNEL_SERVE = "serve"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Sync in progress", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a profile is syncing in the background" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVE, "rclone servers", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while an rclone server (HTTP, WebDAV, FTP…) is running" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Sync results", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Summary after a sync finishes or fails" }
        )
    }

    fun notifId(profileId: String) = 1000 + (profileId.hashCode() and 0xFFFF)

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun progress(ctx: Context, profileId: String, name: String, text: String): Notification {
        val stop = PendingIntent.getBroadcast(
            ctx, notifId(profileId),
            Intent(ctx, StopReceiver::class.java).putExtra(StopReceiver.EXTRA_ID, profileId).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(name)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Stop", stop)
            .build()
    }

    fun foregroundInfo(ctx: Context, profileId: String, name: String, text: String): ForegroundInfo {
        val n = progress(ctx, profileId, name, text)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(notifId(profileId), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId(profileId), n)
    }

    fun update(ctx: Context, profileId: String, name: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId(profileId), progress(ctx, profileId, name, text))
        } catch (_: SecurityException) {}
    }

    fun result(ctx: Context, profileId: String, name: String, text: String, error: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(ctx, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(if (error) "$name – problem" else "$name – done")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            nm.notify(notifId(profileId) + 70000, n)
        } catch (_: SecurityException) {}
    }
}
