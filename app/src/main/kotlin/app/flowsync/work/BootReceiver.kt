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
    }
}
