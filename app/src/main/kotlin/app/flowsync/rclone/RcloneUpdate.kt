package app.flowsync.rclone

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Compares the bundled rclone with the newest official release on GitHub. */
object RcloneUpdate {
    class Info(val installed: String, val latest: String, val newer: Boolean, val message: String)

    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun parse(v: String): List<Int> =
        Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toInt() }.toList()

    fun check(installedLine: String?): Info {
        val installed = Regex("v\\d+(\\.\\d+)+").find(installedLine ?: "")?.value ?: "unknown"
        return try {
            val req = Request.Builder().url("https://api.github.com/repos/rclone/rclone/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            val tag = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("GitHub HTTP ${r.code}")
                JSONObject(r.body!!.string()).getString("tag_name")
            }
            val a = parse(installed); val b = parse(tag)
            var newer = false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (y != x) { newer = y > x; break }
            }
            Info(installed, tag, newer,
                if (newer) "Update available: $installed → $tag" else "rclone is up to date ($installed)")
        } catch (e: Exception) {
            Info(installed, "", false, "Could not check for updates: ${e.message}")
        }
    }
}
