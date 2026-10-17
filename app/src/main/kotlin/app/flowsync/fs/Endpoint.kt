package app.flowsync.fs

import android.content.Context
import app.flowsync.model.EndpointConfig
import app.flowsync.model.Protocol
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream

data class RemoteFile(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * Uniform view over a folder tree (local SAF folder, SMB share, FTP, SFTP, WebDAV).
 * All paths are relative to the configured base folder, '/'-separated, "" = base folder.
 * Implementations are NOT thread-safe; use one instance per thread.
 */
interface Endpoint : Closeable {
    /** Throws if the server / folder can't be reached or access is denied. */
    fun checkAvailable()
    /** Empty list when the directory doesn't exist. */
    fun list(dir: String): List<RemoteFile>
    fun read(path: String): InputStream
    fun write(path: String, input: InputStream, size: Long, modified: Long)
    fun delete(path: String, isDir: Boolean)
    fun mkdirs(dir: String)
}

object PathUtil {
    fun segments(vararg parts: String): List<String> =
        parts.flatMap { it.split('/', '\\') }.filter { it.isNotEmpty() && it != "." }

    fun join(vararg parts: String): String = segments(*parts).joinToString("/")
}

class SyncCancelledException : RuntimeException("Cancelled")

class CancellableInputStream(
    input: InputStream,
    private val cancelled: () -> Boolean,
    private val onBytes: (Int) -> Unit
) : FilterInputStream(input) {
    private fun check() { if (cancelled()) throw SyncCancelledException() }
    override fun read(): Int {
        check()
        val b = super.read()
        if (b >= 0) onBytes(1)
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check()
        val n = super.read(b, off, len)
        if (n > 0) onBytes(n)
        return n
    }
}

object Endpoints {
    fun create(ctx: Context, cfg: EndpointConfig, write: Boolean): Endpoint = when (cfg.protocol) {
        Protocol.LOCAL -> LocalEndpoint(ctx.applicationContext, cfg, write)
        Protocol.SMB -> SmbEndpoint(cfg)
        Protocol.FTP -> FtpEndpoint(cfg)
        Protocol.SFTP -> SftpEndpoint(cfg)
        Protocol.WEBDAV -> WebDavEndpoint(cfg)
        Protocol.RCLONE -> RcloneEndpoint(ctx.applicationContext, cfg)
    }
}
