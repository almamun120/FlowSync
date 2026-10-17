package app.flowsync.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.flowsync.FlowSyncApp

/** Re-arms all schedules after reboot / app update (WorkManager also persists them, this is belt and braces). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? FlowSyncApp ?: return
        Scheduler.applyAll(context, app.repo.profiles.value)
        app.rcloneStore.tasks.value.forEach { Scheduler.applyTask(context, it) }
        // Servers marked "start automatically"
        app.rcloneStore.serves.value.filter { it.autoStart }.forEach {
            try { app.flowsync.rclone.ServeCenter.start(context, it.id) } catch (_: Exception) {}
        }
    }
}
