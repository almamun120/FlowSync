package app.flowsync.rclone

import app.flowsync.fs.PathUtil
import app.flowsync.fs.SyncCancelledException
import app.flowsync.model.EndpointConfig
import app.flowsync.model.FilterMode
import app.flowsync.model.SyncMode
import app.flowsync.model.SyncProfile
import app.flowsync.util.Format
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Runs a profile with rclone's own engine (sync/copy, sync/sync, sync/move, sync/bisync through the rc API)
 * when both sides are rclone remotes: server-side copies, checksums, parallel transfers, bandwidth limits.
 */
object RcloneNativeSync {
    class Result(val summary: String, val transfers: Int, val errors: Int)

    private class JobOutcome(val ok: Boolean, val error: String, val stats: JSONObject, val jobId: Long)

    private fun fsOf(c: EndpointConfig) =
        c.host.trim() + ":" + (if (c.host.trim() == ":local") "/" else "") + PathUtil.join(c.path)

    private fun safeExt(e: String) = e.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Extension filter / hidden files -> rclone filter rules ("+ *.{jpg,png}", "- *" …). */
    private fun rulesOf(p: SyncProfile): List<String> {
        val rules = ArrayList<String>()
        val exts = p.filter.extensions.map { safeExt(it) }.filter { it.isNotEmpty() }
        if (p.filter.skipHidden) { rules += "- .*"; rules += "- **/.*" }
        val glob = if (exts.size == 1) "*." + exts[0] else "*.{" + exts.joinToString(",") + "}"
        when (p.filter.mode) {
            FilterMode.ONLY -> if (exts.isNotEmpty()) { rules += "+ */"; rules += "+ $glob"; rules += "- *" }
            FilterMode.EXCLUDE -> if (exts.isNotEmpty()) rules += "- $glob"
            FilterMode.ALL -> {}
        }
        return rules
    }

    private fun buildFilter(p: SyncProfile): JSONObject {
        val f = JSONObject().put("IgnoreCase", true)
        val rules = JSONArray(); rulesOf(p).forEach { rules.put(it) }
        if (rules.length() > 0) f.put("FilterRule", rules)
        if (p.filter.maxSizeMb > 0) f.put("MaxSize", p.filter.maxSizeMb * 1024L * 1024L)
        return f
    }

    fun run(
        p: SyncProfile,
        cancelled: () -> Boolean,
        log: (String) -> Unit,
        progress: (String, Long, Int) -> Unit
    ): Result {
        val o = p.rclone
        if (o.extraFlags.isNotBlank()) return runCli(p, cancelled, log, progress)
        val bisync = p.mode == SyncMode.BISYNC
        val method = when (p.mode) {
            SyncMode.COPY -> "sync/copy"
            SyncMode.MIRROR -> "sync/sync"
            SyncMode.MOVE -> "sync/move"
            SyncMode.BISYNC -> "sync/bisync"
        }
        val src = fsOf(p.source); val dst = fsOf(p.destination)
        log("rclone native: $method  $src  ->  $dst" + if (o.dryRun) "  (dry run)" else "")
        RcloneDaemon.begin()
        try {
            RcloneDaemon.call("core/bwlimit", JSONObject().put("rate", o.bwLimit.ifBlank { "off" }))
            val cfg = JSONObject().put("Transfers", o.transfers.coerceIn(1, 32)).put("CheckSum", o.checksum).put("DryRun", o.dryRun)
            if (!p.includeSubfolders) cfg.put("MaxDepth", 1)
            val filter = buildFilter(p)

            fun params(resync: Boolean): JSONObject {
                val j = JSONObject()
                if (bisync) { j.put("path1", src).put("path2", dst); if (resync) j.put("resync", true) }
                else j.put("srcFs", src).put("dstFs", dst)
                return j.put("_async", true).put("_config", cfg).put("_filter", filter)
            }

            var out = runJob(method, params(false), cancelled, progress)
            if (!out.ok && bisync && out.error.contains("resync", ignoreCase = true)) {
                log("bisync: first run – performing --resync")
                out = runJob(method, params(true), cancelled, progress)
            }
            logTransferred(out.jobId, log)
            if (!out.ok) throw IOException(out.error.ifBlank { "rclone job failed" })

            val s = out.stats
            val transfers = s.optInt("transfers"); val errors = s.optInt("errors")
            val summary = (if (o.dryRun) "[dry run] " else "") +
                "Transferred $transfers · Checked ${s.optInt("checks")} · Deleted ${s.optInt("deletes")} · Failed $errors" +
                " (${Format.bytes(s.optLong("bytes"))})"
            log("=== Done: $summary ===")
            return Result(summary, transfers, errors)
        } finally {
            RcloneDaemon.end()
        }
    }

    /** Runs `rclone copy|sync|move|bisync` as a command so that ANY rclone flag can be used (profile "extra flags"). */
    private fun runCli(p: SyncProfile, cancelled: () -> Boolean, log: (String) -> Unit, progress: (String, Long, Int) -> Unit): Result {
        val o = p.rclone
        val src = fsOf(p.source); val dst = fsOf(p.destination)
        val cmd = when (p.mode) { SyncMode.COPY -> "copy"; SyncMode.MIRROR -> "sync"; SyncMode.MOVE -> "move"; SyncMode.BISYNC -> "bisync" }

        fun attempt(resync: Boolean): Triple<Int, Int, Boolean> { // copied, errors, needResync
            val a = ArrayList<String>(listOf(cmd, src, dst, "--transfers", o.transfers.coerceIn(1, 32).toString(),
                "--stats", "1s", "--stats-one-line", "-v"))
            if (o.checksum) a += "--checksum"
            if (o.dryRun) a += "--dry-run"
            if (o.bwLimit.isNotBlank()) { a += "--bwlimit"; a += o.bwLimit }
            if (!p.includeSubfolders) { a += "--max-depth"; a += "1" }
            if (p.filter.maxSizeMb > 0) { a += "--max-size"; a += "${p.filter.maxSizeMb}M" }
            val rules = rulesOf(p)
            if (rules.isNotEmpty()) a += "--ignore-case"
            rules.forEach { a += "--filter"; a += it }
            if (resync) a += "--resync"
            a += ShellArgs.split(o.extraFlags)
            log("rclone ${a.joinToString(" ")}")
            val proc = RcloneManager.builder(*a.toTypedArray()).redirectErrorStream(true).start()
            proc.outputStream.close()
            val watcher = Thread {
                while (proc.isAlive) {
                    if (cancelled()) { proc.destroyForcibly(); break }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
            }.also { it.isDaemon = true; it.start() }
            var copied = 0; var errors = 0; var needResync = false; var lastError = ""
            proc.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.isBlank()) continue
                    when {
                        l.contains(": Copied (") || l.contains(": Moved (") || l.contains(": Deleted") || l.contains(": Updated") -> {
                            if (!l.contains(": Deleted")) copied++
                            log(l.substringAfter("INFO  : ").take(300))
                        }
                        l.contains("ERROR") -> { errors++; lastError = l.substringAfter("ERROR : "); log(l.take(300)) }
                        l.contains("resync", ignoreCase = true) && l.contains("must", ignoreCase = true) -> { needResync = true; lastError = l; log(l.take(300)) }
                        l.contains(" ETA ") || l.contains("ETA -") -> progress(l.substringAfter("NOTICE: ").trim().takeLast(100), 0L, copied)
                    }
                }
            }
            val code = proc.waitFor()
            watcher.interrupt()
            if (cancelled()) throw SyncCancelledException()
            if (code != 0 && !needResync) throw IOException(lastError.ifBlank { "rclone exited with code $code" }.take(300))
            return Triple(copied, errors, needResync && code != 0)
        }

        var r = attempt(false)
        if (r.third && p.mode == SyncMode.BISYNC) { log("bisync: first run – performing --resync"); r = attempt(true) }
        val summary = (if (o.dryRun) "[dry run] " else "") + "Transferred ${r.first} · Failed ${r.second}"
        log("=== Done: $summary ===")
        return Result(summary, r.first, r.second)
    }

    private fun runJob(method: String, params: JSONObject, cancelled: () -> Boolean, progress: (String, Long, Int) -> Unit): JobOutcome {
        val jobId = RcloneDaemon.call(method, params).getLong("jobid")
        while (true) {
            if (cancelled()) {
                runCatching { RcloneDaemon.call("job/stop", JSONObject().put("jobid", jobId)) }
                throw SyncCancelledException()
            }
            val st = RcloneDaemon.call("job/status", JSONObject().put("jobid", jobId))
            val stats = runCatching { RcloneDaemon.call("core/stats", JSONObject().put("group", "job/$jobId")) }.getOrDefault(JSONObject())
            val cur = stats.optJSONArray("transferring")?.optJSONObject(0)
            val msg = if (cur != null) "${cur.optString("name")}  ${cur.optInt("percentage")}%" else "Working…"
            progress(msg, stats.optLong("bytes"), stats.optInt("transfers"))
            if (st.optBoolean("finished")) return JobOutcome(st.optBoolean("success"), st.optString("error"), stats, jobId)
            Thread.sleep(1000)
        }
    }

    private fun logTransferred(jobId: Long, log: (String) -> Unit) {
        try {
            val arr = RcloneDaemon.call("core/transferred", JSONObject().put("group", "job/$jobId")).optJSONArray("transferred") ?: return
            for (i in 0 until minOf(arr.length(), 300)) {
                val t = arr.getJSONObject(i)
                val err = t.optString("error")
                if (err.isNotEmpty()) log("FAILED  ${t.optString("name")}: $err")
                else log("COPIED  ${t.optString("name")}  (${Format.bytes(t.optLong("size"))})")
            }
            if (arr.length() > 300) log("… ${arr.length() - 300} more")
        } catch (_: Exception) {}
    }
}
