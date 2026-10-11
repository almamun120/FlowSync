package app.flowsync.fs

import app.flowsync.model.EndpointConfig
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB 2/3 client (smbj). Pure user-space, no root. */
class SmbEndpoint(private val cfg: EndpointConfig) : Endpoint {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private val base = PathUtil.join(cfg.path)

    private fun disk(): DiskShare {
        share?.let { if (it.isConnected) return it }
        close()
        if (cfg.host.isBlank()) throw IOException("Host is empty")
        if (cfg.share.isBlank()) throw IOException("Share name is empty")
        val t = cfg.timeoutSec.toLong()
        val config = SmbConfig.builder()
            .withTimeout(t, TimeUnit.SECONDS)
            .withSoTimeout(t, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(config)
        client = c
        val conn = c.connect(cfg.host.trim(), if (cfg.port > 0) cfg.port else 445)
        connection = conn
        val auth = if (cfg.username.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(cfg.username, cfg.password.toCharArray(), cfg.domain)
        val s = conn.authenticate(auth)
        session = s
        val sh = s.connectShare(cfg.share.trim()) as? DiskShare
            ?: throw IOException("'${cfg.share}' is not a file share")
        share = sh
        return sh
    }

    private fun full(rel: String): String = PathUtil.join(base, rel).replace('/', '\\')

    override fun checkAvailable() { disk() }

    override fun list(dir: String): List<RemoteFile> {
        val d = disk()
        val p = full(dir)
        if (p.isNotEmpty() && !d.folderExists(p)) return emptyList()
        val out = ArrayList<RemoteFile>()
        for (e in d.list(p)) {
            val n = e.fileName
            if (n == "." || n == "..") continue
            val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            out += RemoteFile(n, isDir, e.endOfFile, e.lastWriteTime.toEpochMillis())
        }
        return out
    }

    override fun read(path: String): InputStream {
        val f = disk().openFile(
            full(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        )
        val ins = f.inputStream
        return object : FilterInputStream(ins) {
            override fun close() { try { super.close() } finally { f.close() } }
        }
    }

    override fun write(path: String, input: InputStream, size: Long, modified: Long) {
        mkdirs(path.substringBeforeLast('/', ""))
        val f = disk().openFile(
            full(path), EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null
        )
        try {
            f.outputStream.use { out -> input.copyTo(out, 256 * 1024) }
        } finally {
            f.close()
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        val d = disk()
        if (isDir) d.rmdir(full(path), false) else d.rm(full(path))
    }

    private val made = HashSet<String>()
    override fun mkdirs(dir: String) {
        val p = full(dir)
        if (p.isEmpty() || p in made) return
        val d = disk()
        var cur = ""
        for (seg in PathUtil.segments(base, dir)) {
            cur = if (cur.isEmpty()) seg else "$cur\\$seg"
            if (cur in made) continue
            if (!d.folderExists(cur)) d.mkdir(cur)
            made += cur
        }
    }

    override fun close() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null; session = null; connection = null; client = null
        made.clear()
    }
}
