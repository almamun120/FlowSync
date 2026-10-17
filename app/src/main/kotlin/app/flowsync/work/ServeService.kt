package app.flowsync.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.flowsync.FlowSyncApp
import app.flowsync.R
import app.flowsync.rclone.RcloneManager
import app.flowsync.rclone.ServeCenter
import app.flowsync.rclone.ServeState
import app.flowsync.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that keeps rclone servers (HTTP, WebDAV, FTP, SFTP, DLNA, S3, NFS, Web GUI) alive
 * in the background. Start/stop individual servers from the app; the notification has "Stop all".
 */
class ServeService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ID)?.let { startServe(it) }
            ACTION_STOP_ALL -> stopAll()
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { /* not allowed right now (background start restrictions) */ }
    }

    private fun buildNotification(): Notification {
        val app = applicationContext as FlowSyncApp
        val running = processes.keys.mapNotNull { app.rcloneStore.getServe(it) }
        val text = if (running.isEmpty()) "Starting…" else running.joinToString("  ·  ") {
            it.name.ifBlank { it.kind } + " " + (ServeCenter.urlsFor(it).firstOrNull() ?: "")
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServeService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, Notifications.CHANNEL_SERVE)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("rclone servers running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop all", stop)
            .build()
    }

    /** Updates the notification, or shuts the service down when nothing is running. */
    fun refresh() {
        if (processes.isEmpty()) {
            releaseLocks()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        } else {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(NOTIF_ID, buildNotification())
            } catch (_: SecurityException) {}
        }
    }

    private fun acquireLocks() {
        try {
            if (wake == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flowsync:serve").apply { setReferenceCounted(false); acquire() }
            }
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flowsync:serve").apply { setReferenceCounted(false); acquire() }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        wake = null; wifi = null
    }

    private fun startServe(id: String) {
        val app = applicationContext as FlowSyncApp
        val cfg = app.rcloneStore.getServe(id) ?: return
        if (processes.containsKey(id)) return
        ServeCenter.logs.remove(id)
        try {
            if (cfg.kind != "webgui" && cfg.target.isBlank()) throw IllegalArgumentException("No folder/remote selected")
            val args = ServeCenter.buildArgs(cfg)
            ServeCenter.appendLog(id, "$ rclone ${args.joinToString(" ") { if (it == cfg.pass && cfg.pass.isNotEmpty()) "***" else it }}\n")
            val p = RcloneManager.builder(*args.toTypedArray()).redirectErrorStream(true).start()
            p.outputStream.close()
            processes[id] = p
            acquireLocks()
            ServeCenter.set(id, ServeState(true, ServeCenter.urlsFor(cfg)))
            Thread {
                try {
                    val buf = CharArray(1024)
                    p.inputStream.bufferedReader().use { r ->
                        while (true) { val n = r.read(buf); if (n < 0) break; ServeCenter.appendLog(id, String(buf, 0, n)) }
                    }
                } catch (_: Exception) {}
                val code = try { p.waitFor() } catch (_: Exception) { -1 }
                val wasStopped = processes[id] !== p
                processes.remove(id, p)
                ServeCenter.set(id, ServeState(false, error = if (wasStopped || code == 0 || code == 143 || code == 137) "" else "Stopped (exit $code) – see log"))
                instance?.refresh()
            }.also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            ServeCenter.set(id, ServeState(false, error = e.message ?: "Failed to start"))
            ServeCenter.appendLog(id, "✗ ${e.message}\n")
        }
    }

    override fun onDestroy() {
        processes.values.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        processes.clear()
        releaseLocks()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.flowsync.serve.START"
        const val ACTION_STOP_ALL = "app.flowsync.serve.STOP_ALL"
        const val EXTRA_ID = "serve_id"
        private const val NOTIF_ID = 4242
        private val processes = ConcurrentHashMap<String, Process>()
        @Volatile var instance: ServeService? = null

        fun stopServe(id: String) {
            val p = processes.remove(id)
            try { p?.destroyForcibly() } catch (_: Exception) {}
            ServeCenter.set(id, ServeState(false))
            instance?.refresh()
        }

        fun stopAll() {
            val ids = processes.keys.toList()
            ids.forEach { stopServe(it) }
        }
    }
}
