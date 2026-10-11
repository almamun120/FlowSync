package app.flowsync.util

import android.net.Uri
import android.provider.DocumentsContract
import app.flowsync.model.EndpointConfig
import app.flowsync.model.Protocol
import java.util.Locale

object Format {
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun localPath(uri: String): String {
        if (uri.isBlank()) return "No folder selected"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            val vol = id.substringBefore(':')
            val rest = id.substringAfter(':', "")
            val root = if (vol == "primary") "Internal storage" else "Storage $vol"
            if (rest.isEmpty()) root else "$root/$rest"
        } catch (e: Exception) { uri }
    }

    fun summary(c: EndpointConfig): String {
        val p = c.path.trim('/')
        val tail = if (p.isEmpty()) "" else "/$p"
        return when (c.protocol) {
            Protocol.LOCAL -> localPath(c.localUri)
            Protocol.SMB -> "SMB  ${c.host}/${c.share}$tail"
            Protocol.FTP -> "${if (c.secure) "FTPS" else "FTP"}  ${c.host}$tail"
            Protocol.SFTP -> "SFTP  ${c.host}$tail"
            Protocol.WEBDAV -> "WebDAV  ${c.host}$tail"
            Protocol.GDRIVE -> "Google Drive  ${c.username.ifBlank { "(not signed in)" }}$tail"
        }
    }
}
