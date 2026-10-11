package app.flowsync.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_ID)?.let { SyncCenter.requestStop(it) }
    }
    companion object { const val EXTRA_ID = "profile_id" }
}
