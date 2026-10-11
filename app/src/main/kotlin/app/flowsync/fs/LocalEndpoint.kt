package app.flowsync.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import app.flowsync.model.EndpointConfig
import java.io.IOException
import java.io.InputStream

/**
 * Local storage through the Storage Access Framework. The user picks a folder once; the app keeps a
 * persisted URI permission, so it works in the background without root and without "all files access".
 */
class LocalEndpoint(
    ctx: Context,
    private val cfg: EndpointConfig,
    private val needWrite: Boolean
) : Endpoint {
    private val cr: ContentResolver = ctx.contentResolver
    private val tree: Uri = Uri.parse(cfg.localUri)
    private val rootId: String by lazy { DocumentsContract.getTreeDocumentId(tree) }
    private val ids = HashMap<String, String>()

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun childrenUri(id: String): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)

    override fun checkAvailable() {
        if (cfg.localUri.isBlank()) throw IOException("No folder selected")
        val perm = cr.persistedUriPermissions.firstOrNull { it.uri == tree }
            ?: throw IOException("Folder permission lost – open the profile and select the folder again")
        if (needWrite && !perm.isWritePermission) throw IOException("No write permission – select the folder again")
        val ok = try {
            cr.query(docUri(rootId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) { false }
        if (!ok) throw IOException("Folder not accessible (deleted or storage unmounted)")
    }

    private fun resolve(rel: String): String? {
        if (rel.isEmpty()) return rootId
        ids[rel]?.let { return it }
        val parent = rel.substringBeforeLast('/', "")
        if (resolve(parent) == null) return null
        list(parent)
        return ids[rel]
    }

    override fun list(dir: String): List<RemoteFile> {
        val id = resolve(dir) ?: return emptyList()
        val out = ArrayList<RemoteFile>()
        val proj = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
        cr.query(childrenUri(id), proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val did = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mod = if (c.isNull(4)) 0L else c.getLong(4)
                ids[PathUtil.join(dir, name)] = did
                out += RemoteFile(name, isDir, size, mod)
            }
        }
        return out
    }

    override fun read(path: String): InputStream {
        val id = resolve(path) ?: throw IOException("File not found: $path")
        return cr.openInputStream(docUri(id)) ?: throw IOException("Cannot open $path")
    }

    override fun write(path: String, input: InputStream, size: Long, modified: Long) {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        mkdirs(parent)
        val existing = resolve(path)
        val uri = if (existing != null) {
            docUri(existing)
        } else {
            val parentId = resolve(parent) ?: throw IOException("Parent folder missing: $parent")
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val created = DocumentsContract.createDocument(cr, docUri(parentId), mime, name)
                ?: throw IOException("Cannot create $name")
            ids[path] = DocumentsContract.getDocumentId(created)
            created
        }
        (cr.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $name")).use { out ->
            input.copyTo(out, 256 * 1024)
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        val id = resolve(path) ?: return
        DocumentsContract.deleteDocument(cr, docUri(id))
        ids.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun mkdirs(dir: String) {
        var cur = ""
        for (seg in PathUtil.segments(dir)) {
            val next = if (cur.isEmpty()) seg else "$cur/$seg"
            if (resolve(next) == null) {
                val parentId = resolve(cur) ?: throw IOException("Parent folder missing: $cur")
                val u = DocumentsContract.createDocument(cr, docUri(parentId), Document.MIME_TYPE_DIR, seg)
                    ?: throw IOException("Cannot create folder $seg")
                ids[next] = DocumentsContract.getDocumentId(u)
            }
            cur = next
        }
    }

    override fun close() { ids.clear() }
}
