package app.flowsync.fs

import app.flowsync.model.EndpointConfig
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** FTP / explicit FTPS client (commons-net). */
class FtpEndpoint(private val cfg: EndpointConfig) : Endpoint {
    private var ftp: FTPClient? = null
    private val base = PathUtil.join(cfg.path)
    private val made = HashSet<String>()

    private fun client(): FTPClient {
        ftp?.let { if (it.isConnected) return it }
        close()
        if (cfg.host.isBlank()) throw IOException("Host is empty")
        val ms = cfg.timeoutSec * 1000
        val c: FTPClient = if (cfg.secure) FTPSClient("TLS", false).also {
            // Home servers usually use self-signed certificates.
            it.trustManager = TrustManagerUtils.getAcceptAllTrustManager()
        } else FTPClient()
        c.connectTimeout = ms
        c.defaultTimeout = ms
        c.controlEncoding = "UTF-8"
        c.connect(cfg.host.trim(), if (cfg.port > 0) cfg.port else 21)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            c.disconnect()
            throw IOException("FTP server refused connection: ${c.replyString.trim()}")
        }
        val user = cfg.username.ifBlank { "anonymous" }
        val pass = if (cfg.username.isBlank()) "anonymous@" else cfg.password
        if (!c.login(user, pass)) {
            val msg = c.replyString.trim()
            try { c.disconnect() } catch (_: Exception) {}
            throw IOException("FTP login failed: $msg")
        }
        if (c is FTPSClient) {
            c.execPBSZ(0)
            c.execPROT("P")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.setDataTimeout(Duration.ofMillis(ms.toLong() * 3))
        c.setControlKeepAliveTimeout(Duration.ofSeconds(30))
        ftp = c
        return c
    }

    private fun full(rel: String): String = "/" + PathUtil.join(base, rel)

    override fun checkAvailable() { client() }

    override fun list(dir: String): List<RemoteFile> {
        val c = client()
        val p = full(dir)
        var arr: Array<FTPFile>? = try { c.mlistDir(p) } catch (e: Exception) { null }
        if (arr == null || (arr.isEmpty() && !FTPReply.isPositiveCompletion(c.replyCode))) {
            arr = c.listFiles(p)
        }
        val out = ArrayList<RemoteFile>()
        for (f in arr ?: emptyArray()) {
            val n = f.name ?: continue
            if (n == "." || n == ".." || f.isSymbolicLink) continue
            out += RemoteFile(n.substringAfterLast('/'), f.isDirectory, f.size, f.timestamp?.timeInMillis ?: 0L)
        }
        return out
    }

    override fun read(path: String): InputStream {
        val c = client()
        val ins = c.retrieveFileStream(full(path))
            ?: throw IOException("Cannot read $path: ${c.replyString.trim()}")
        return object : FilterInputStream(ins) {
            override fun close() {
                try { super.close() } finally {
                    try { c.completePendingCommand() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun write(path: String, input: InputStream, size: Long, modified: Long) {
        mkdirs(path.substringBeforeLast('/', ""))
        val c = client()
        val p = full(path)
        if (!c.storeFile(p, input)) throw IOException("Upload failed: ${c.replyString.trim()}")
        if (modified > 0) {
            try {
                val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                c.setModificationTime(p, fmt.format(Date(modified)))
            } catch (_: Exception) {}
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        val c = client()
        val ok = if (isDir) c.removeDirectory(full(path)) else c.deleteFile(full(path))
        if (!ok) throw IOException("Delete failed: ${c.replyString.trim()}")
    }

    override fun mkdirs(dir: String) {
        val c = client()
        var cur = ""
        for (seg in PathUtil.segments(base, dir)) {
            cur = "$cur/$seg"
            if (cur in made) continue
            c.makeDirectory(cur) // fails harmlessly when it already exists
            made += cur
        }
    }

    override fun close() {
        try { ftp?.let { if (it.isConnected) { try { it.logout() } catch (_: Exception) {}; it.disconnect() } } } catch (_: Exception) {}
        ftp = null
        made.clear()
    }
}
