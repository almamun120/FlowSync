package app.flowsync.rclone

import android.content.Context
import android.content.Intent
import app.flowsync.model.ServeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

data class ServeState(val running: Boolean, val urls: List<String> = emptyList(), val error: String = "")

/** State + control of rclone servers (serve http/webdav/ftp/sftp/dlna/s3/nfs and the Web GUI). */
object ServeCenter {
    val KINDS = listOf(
        Kind("http", "HTTP file server", 8080, "http"),
        Kind("webdav", "WebDAV server", 8081, "http"),
        Kind("ftp", "FTP server", 2121, "ftp"),
        Kind("sftp", "SFTP server", 2022, "sftp"),
        Kind("dlna", "DLNA media server", 7879, ""),
        Kind("s3", "S3-compatible server", 8333, "http"),
        Kind("nfs", "NFS server", 2049, ""),
        Kind("webgui", "rclone Web GUI (full control)", 5572, "http")
    )
    class Kind(val id: String, val label: String, val defaultPort: Int, val scheme: String)

    private val _states = MutableStateFlow<Map<String, ServeState>>(emptyMap())
    val states: StateFlow<Map<String, ServeState>> = _states.asStateFlow()
    val logs = ConcurrentHashMap<String, StringBuilder>()

    fun set(id: String, s: ServeState) { _states.update { it + (id to s) } }
    fun isRunning(id: String) = _states.value[id]?.running == true

    fun appendLog(id: String, chunk: String) {
        val sb = logs.getOrPut(id) { StringBuilder() }
        synchronized(sb) { sb.append(chunk); if (sb.length > 20000) sb.delete(0, sb.length - 20000) }
    }
    fun log(id: String): String = logs[id]?.let { synchronized(it) { it.toString() } } ?: ""

    fun start(ctx: Context, id: String) {
        val i = Intent(ctx, app.flowsync.work.ServeService::class.java)
            .setAction(app.flowsync.work.ServeService.ACTION_START).putExtra(app.flowsync.work.ServeService.EXTRA_ID, id)
        androidx.core.content.ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(id: String) { app.flowsync.work.ServeService.stopServe(id) }
    fun stopAll() { app.flowsync.work.ServeService.stopAll() }

    /** Local IPv4 addresses of this phone (Wi-Fi etc.). */
    fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    fun urlsFor(c: ServeConfig): List<String> {
        val k = KINDS.firstOrNull { it.id == c.kind } ?: return emptyList()
        val hosts = if (c.lan) lanAddresses().ifEmpty { listOf("0.0.0.0") } else listOf("127.0.0.1")
        return if (k.scheme.isEmpty()) hosts.map { "$it:${c.port}" } else hosts.map { "${k.scheme}://$it:${c.port}" }
    }

    /** Builds the rclone command line for a server definition. */
    fun buildArgs(c: ServeConfig): List<String> {
        val bind = (if (c.lan) "0.0.0.0" else "127.0.0.1") + ":" + c.port
        val a = ArrayList<String>()
        if (c.kind == "webgui") {
            a += listOf("rcd", "--rc-web-gui", "--rc-web-gui-no-open-browser", "--rc-addr", bind)
            if (c.user.isNotBlank()) { a += listOf("--rc-user", c.user, "--rc-pass", c.pass) } else a += "--rc-no-auth"
        } else {
            a += listOf("serve", c.kind, c.target, "--addr", bind)
            when (c.kind) {
                "http", "webdav", "ftp", "sftp" -> {
                    if (c.user.isNotBlank()) a += listOf("--user", c.user, "--pass", c.pass)
                    else if (c.kind == "sftp") a += "--no-auth"
                }
                "s3" -> if (c.user.isNotBlank()) a += listOf("--auth-key", "${c.user},${c.pass}")
            }
            if (c.readOnly) a += "--read-only"
        }
        a += ShellArgs.split(c.extraFlags)
        return a
    }
}
