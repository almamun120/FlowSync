package app.flowsync.work

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.flowsync.FlowSyncApp
import app.flowsync.data.LogStore
import app.flowsync.rclone.RcloneManager
import app.flowsync.rclone.ShellArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Runs a saved rclone command (any command / flags) in the background, now or on a schedule. */
class RcloneTaskWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID).orEmpty()
        val name = (applicationContext as FlowSyncApp).rcloneStore.getTask(id)?.name ?: "rclone"
        return Notifications.foregroundInfo(applicationContext, id, name, "Preparing…")
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = applicationContext as FlowSyncApp
        val task = app.rcloneStore.getTask(id) ?: return Result.failure()
        if (!SyncCenter.tryStart(id)) return Result.success()
        val job = currentCoroutineContext()[Job]!!
        var wake: PowerManager.WakeLock? = null
        try {
            try { setForeground(Notifications.foregroundInfo(applicationContext, id, task.name, "Running…")) } catch (_: Exception) {}
            try {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flowsync:task").apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
            } catch (_: Exception) {}
            val summary = withContext(Dispatchers.IO) { runCommand(task.id, task.name, task.command, job) }
            app.rcloneStore.updateTaskRun(id, System.currentTimeMillis(), summary)
            Notifications.result(applicationContext, id, task.name, summary, summary.startsWith("Failed"))
            return Result.success()
        } finally {
            try { if (wake?.isHeld == true) wake.release() } catch (_: Exception) {}
            SyncCenter.finish(id)
            try {
                (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(Notifications.notifId(id))
            } catch (_: Exception) {}
        }
    }

    private fun runCommand(id: String, name: String, command: String, job: Job): String {
        LogStore.rotate(id)
        val args = ShellArgs.split(command).let { if (it.firstOrNull() == "rclone") it.drop(1) else it }
        if (args.isEmpty()) return "Failed: empty command"
        LogStore.append(id, "=== rclone ${args.joinToString(" ")} ===")
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val p = RcloneManager.builder(*args.toTypedArray()).redirectErrorStream(true).start()
            p.outputStream.close()
            val watcher = Thread {
                while (p.isAlive) {
                    if (!job.isActive || SyncCenter.isStopRequested(id)) { p.destroyForcibly(); break }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
            }.also { it.isDaemon = true; it.start() }
            var last = 0L
            p.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.isBlank()) continue
                    LogStore.append(id, l)
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 800) {
                        last = now
                        SyncCenter.update(id, RunState(l.takeLast(120)))
                        Notifications.update(applicationContext, id, name, l.takeLast(120))
                    }
                }
            }
            val code = p.waitFor()
            watcher.interrupt()
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000
            val stopped = SyncCenter.isStopRequested(id) || !job.isActive
            val s = when {
                stopped -> "Stopped"
                code == 0 -> "Finished OK (${secs}s)"
                else -> "Failed (exit $code)"
            }
            LogStore.append(id, "=== $s ===")
            s
        } catch (e: Exception) {
            LogStore.append(id, "=== Failed: ${e.message} ===")
            "Failed: ${e.message}"
        }
    }

    companion object { const val KEY_ID = "task_id" }
}
