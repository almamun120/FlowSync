package app.flowsync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.flowsync.model.SyncProfile
import java.util.concurrent.TimeUnit

object Scheduler {
    private fun periodicName(id: String) = "periodic_$id"

    /** Manual run: ignores Wi-Fi/charging conditions, runs as expedited foreground work. */
    fun runNow(ctx: Context, p: SyncProfile) {
        val c = Constraints.Builder()
            .setRequiredNetworkType(if (p.needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_ID to p.id))
            .setConstraints(c)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("run_${p.id}", ExistingWorkPolicy.KEEP, req)
    }

    fun apply(ctx: Context, p: SyncProfile) {
        val wm = WorkManager.getInstance(ctx)
        val s = p.schedule
        if (p.enabled && s.enabled && s.intervalMinutes > 0) {
            val network = when {
                !p.needsNetwork -> NetworkType.NOT_REQUIRED
                s.wifiOnly -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            }
            val c = Constraints.Builder()
                .setRequiredNetworkType(network)
                .setRequiresCharging(s.chargingOnly)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(15, s.intervalMinutes).toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(SyncWorker.KEY_ID to p.id))
                .setConstraints(c)
                .build()
            wm.enqueueUniquePeriodicWork(periodicName(p.id), ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(periodicName(p.id))
        }
    }

    fun runTaskNow(ctx: Context, taskId: String) {
        val req = OneTimeWorkRequestBuilder<RcloneTaskWorker>()
            .setInputData(workDataOf(RcloneTaskWorker.KEY_ID to taskId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("task_run_$taskId", ExistingWorkPolicy.KEEP, req)
    }

    fun applyTask(ctx: Context, t: app.flowsync.model.RcloneTask) {
        val wm = WorkManager.getInstance(ctx)
        val name = "task_periodic_${t.id}"
        if (t.intervalMinutes > 0) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (t.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            val req = PeriodicWorkRequestBuilder<RcloneTaskWorker>(maxOf(15, t.intervalMinutes).toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(RcloneTaskWorker.KEY_ID to t.id)).setConstraints(c).build()
            wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(name)
    }

    fun cancelTask(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork("task_periodic_$id")
        SyncCenter.requestStop(id)
    }

    fun cancel(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(periodicName(id))
        SyncCenter.requestStop(id)
    }

    fun applyAll(ctx: Context, list: List<SyncProfile>) = list.forEach { apply(ctx, it) }
}
