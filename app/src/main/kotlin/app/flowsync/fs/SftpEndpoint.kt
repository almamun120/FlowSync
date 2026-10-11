package app.flowsync.fs

import app.flowsync.model.EndpointConfig
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.IOException
import java.io.InputStream

/** SFTP over SSH (JSch fork). Password and/or private-key authentication. */
class SftpEndpoint(private val cfg: EndpointConfig) : Endpoint {
    private var session: Session? = null
    private var channel: ChannelSftp? = null
    private val made = HashSet<String>()

    private fun ch(): ChannelSftp {
        channel?.let { if (it.isConnected && session?.isConnected == true) return it }
        close()
        if (cfg.host.isBlank()) throw IOException("Host is empty")
        val ms = cfg.timeoutSec * 1000
        val jsch = JSch()
        if (cfg.privateKey.isNotBlank()) {
            val pass = cfg.password.takeIf { it.isNotEmpty() }?.toByteArray()
            jsch.addIdentity("flowsync-key", cfg.privateKey.toByteArray(), null, pass)
        }
        val s = jsch.getSession(cfg.username, cfg.host.trim(), if (cfg.port > 0) cfg.port else 22)
        if (cfg.password.isNotEmpty()) s.setPassword(cfg.password)
        s.setConfig("StrictHostKeyChecking", "no") // home/NAS servers: accept host key
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.timeout = ms
        s.setServerAliveInterval(15000)
        s.connect(ms)
        session = s
        val c = s.openChannel("sftp") as ChannelSftp
        c.connect(ms)
        channel = c
        return c
    }

    private fun full(rel: String): String {
        val b = cfg.path.trim()
        val j = PathUtil.join(b, rel)
        return if (b.startsWith("/")) "/$j" else j.ifEmpty { "." }
    }

    override fun checkAvailable() { ch() }

    override fun list(dir: String): List<RemoteFile> {
        val c = ch()
        val res = try {
            c.ls(full(dir))
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return emptyList() else throw e
        }
        val out = ArrayList<RemoteFile>()
        for (o in res) {
            val e = o as ChannelSftp.LsEntry
            val n = e.filename
            if (n == "." || n == ".." || e.attrs.isLink()) continue
            out += RemoteFile(n, e.attrs.isDir(), e.attrs.getSize(), e.attrs.getMTime().toLong() * 1000L)
        }
        return out
    }

    override fun read(path: String): InputStream = ch().get(full(path))

    override fun write(path: String, input: InputStream, size: Long, modified: Long) {
        mkdirs(path.substringBeforeLast('/', ""))
        val c = ch()
        val p = full(path)
        c.put(input, p, ChannelSftp.OVERWRITE)
        if (modified > 0) {
            try { c.setMtime(p, (modified / 1000).toInt()) } catch (_: Exception) {}
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        val c = ch()
        if (isDir) c.rmdir(full(path)) else c.rm(full(path))
    }

    override fun mkdirs(dir: String) {
        val c = ch()
        val abs = cfg.path.trim().startsWith("/")
        var cur = if (abs) "" else null
        for (seg in PathUtil.segments(cfg.path, dir)) {
            cur = if (cur == null) seg else "$cur/$seg"
            if (cur in made) continue
            try { c.stat(cur) } catch (e: SftpException) { c.mkdir(cur) }
            made += cur
        }
    }

    override fun close() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null; session = null
        made.clear()
    }
}
