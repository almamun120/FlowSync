package app.flowsync.rclone

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One shared `rclone rcd` (remote-control daemon) bound to 127.0.0.1 with a random port and password.
 * All rclone endpoints talk to it over HTTP; it is stopped automatically when idle.
 */
object RcloneDaemon {
    class Conn(val port: Int, val user: String, val pass: String) {
        val auth: String get() = Credentials.basic(user, pass)
        fun url(path: String) = "http://127.0.0.1:$port/$path"
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var process: Process? = null
    private var conn: Conn? = null
    private val inflight = AtomicInteger(0)
    @Volatile private var lastUse = System.currentTimeMillis()
    private var watchdog: Thread? = null
    private val tail = StringBuilder()

    @Synchronized
    fun ensure(): Conn {
        lastUse = System.currentTimeMillis()
        conn?.let { c -> if (process?.isAlive == true) return c }
        stopLocked()
        val port = ServerSocket(0).use { it.localPort }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Conn(port, "flowsync", bytes.joinToString("") { "%02x".format(it) })
        val p = RcloneManager.builder(
            "rcd", "--rc-addr", "127.0.0.1:$port", "--rc-user", c.user, "--rc-pass", c.pass,
            "--rc-serve", "--log-level", "NOTICE"
        ).redirectErrorStream(true).start()
        p.outputStream.close()
        process = p
        tail.setLength(0)
        Thread {
            try {
                val buf = CharArray(1024)
                p.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        synchronized(tail) { tail.append(buf, 0, n); if (tail.length > 4000) tail.delete(0, tail.length - 4000) }
                    }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()

        var ready = false
        for (i in 0 until 60) {
            if (!p.isAlive) break
            try {
                http.newCall(Request.Builder().url(c.url("rc/noop")).header("Authorization", c.auth)
                    .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { ready = it.isSuccessful }
            } catch (_: Exception) {}
            if (ready) break
            Thread.sleep(250)
        }
        if (!ready) {
            val msg = synchronized(tail) { tail.toString().takeLast(400) }
            stopLocked()
            throw IOException("rclone daemon failed to start. $msg")
        }
        conn = c
        startWatchdog()
        return c
    }

    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread {
            while (true) {
                Thread.sleep(30_000)
                synchronized(this) {
                    if (process?.isAlive != true) { watchdog = null; return@Thread }
                    if (inflight.get() == 0 && System.currentTimeMillis() - lastUse > 120_000) {
                        stopLocked(); watchdog = null; return@Thread
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null; conn = null
    }

    fun begin() { inflight.incrementAndGet(); lastUse = System.currentTimeMillis() }
    fun end() { inflight.decrementAndGet(); lastUse = System.currentTimeMillis() }

    /** Calls an rc method ("operations/list" …) and returns the JSON reply. */
    fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
        val c = ensure()
        begin()
        try {
            val req = Request.Builder().url(c.url(method)).header("Authorization", c.auth)
                .post(params.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = try { JSONObject(text).optString("error", text) } catch (e: Exception) { text }
                    throw IOException(msg.ifBlank { "rclone: HTTP ${r.code}" })
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } finally { end() }
    }
}
