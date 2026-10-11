package app.flowsync.work

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.flowsync.FlowSyncApp
import app.flowsync.data.LogStore
import app.flowsync.fs.Endpoints
import app.flowsync.fs.SyncCancelledException
import app.flowsync.model.SyncMode
import app.flowsync.model.SyncProfile
import app.flowsync.sync.SyncEngine
import app.flowsync.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Runs one profile as foreground (data-sync) work so Android doesn't kill it in the background.
 * Used for both "Sync now" and scheduled runs.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private class Outcome(val summary: String, val notify: Boolean, val error: Boolean)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID).orEmpty()
        val name = (applicationContext as FlowSyncApp).repo.get(id)?.name ?: "FlowSync"
        return Notifications.foregroundInfo(applicationContext, id, name, "Preparing…")
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = applicationContext as FlowSyncApp
        val profile = app.repo.get(id) ?: return Result.failure()
        if (!SyncCenter.tryStart(id)) return Result.success() // already running

        val job = currentCoroutineContext()[Job]!!
        var wake: PowerManager.WakeLock? = null
        var wifi: WifiManager.WifiLock? = null
        try {
            try { setForeground(Notifications.foregroundInfo(applicationContext, id, profile.name, "Starting…")) }
            catch (e: Exception) { Log.w("FlowSync", "Foreground start refused: ${e.message}") }

            try {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flowsync:sync").apply {
                    setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L)
                }
                val wm = applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flowsync:wifi").apply {
                    setReferenceCounted(false); acquire()
                }
            } catch (e: Exception) { Log.w("FlowSync", "Lock failed: ${e.message}") }

            val outcome = withContext(Dispatchers.IO) { runSync(profile, job) }
            app.repo.updateLastRun(id, System.currentTimeMillis(), outcome.summary)
            if (outcome.notify) Notifications.result(applicationContext, id, profile.name, outcome.summary, outcome.error)
            return Result.success()
        } finally {
            try { if (wake?.isHeld == true) wake.release() } catch (_: Exception) {}
            try { if (wifi?.isHeld == true) wifi.release() } catch (_: Exception) {}
            SyncCenter.finish(id)
            try {
                (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(Notifications.notifId(id))
            } catch (_: Exception) {}
        }
    }

    private fun runSync(profile: SyncProfile, job: Job): Outcome {
        val id = profile.id
        val ctx = applicationContext
        LogStore.rotate(id)
        LogStore.append(id, "=== Sync started: ${profile.name} (${profile.mode.label}) ===")
        val t0 = SystemClock.elapsedRealtime()
        var lastUi = 0L
        var src: app.flowsync.fs.Endpoint? = null
        var dst: app.flowsync.fs.Endpoint? = null
        val cancelled = { !job.isActive || SyncCenter.isStopRequested(id) }
        return try {
            src = Endpoints.create(ctx, profile.source, profile.mode == SyncMode.MOVE)
            dst = Endpoints.create(ctx, profile.destination, true)
            val engine = SyncEngine(profile, src, dst, cancelled,
                log = { LogStore.append(id, it) },
                progress = { msg, st ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 700) {
                        lastUi = now
                        SyncCenter.update(id, RunState(msg, st.copied, st.failed, st.bytes))
                        Notifications.update(ctx, id, profile.name, "$msg  ·  ${st.copied} copied, ${Format.bytes(st.bytes)}")
                    }
                })
            val st = engine.run()
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000
            val summary = "Copied ${st.copied} · Skipped ${st.skipped} · Deleted ${st.deleted} · Failed ${st.failed}" +
                " (${Format.bytes(st.bytes)}, ${secs}s)"
            LogStore.append(id, "=== Done: $summary ===")
            Outcome(summary, notify = st.copied > 0 || st.failed > 0 || st.deleted > 0, error = st.failed > 0)
        } catch (e: SyncCancelledException) {
            LogStore.append(id, "=== Stopped ===")
            Outcome("Stopped", notify = false, error = false)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            LogStore.append(id, "=== Failed: $msg ===")
            Outcome("Failed: $msg", notify = true, error = true)
        } finally {
            try { src?.close() } catch (_: Exception) {}
            try { dst?.close() } catch (_: Exception) {}
        }
    }

    companion object { const val KEY_ID = "profile_id" }
}
