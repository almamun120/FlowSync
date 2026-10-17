package app.flowsync.fs

import app.flowsync.model.EndpointConfig
import app.flowsync.rclone.RcloneDaemon
import app.flowsync.rclone.RcloneManager
import android.content.Context
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Any rclone remote (Google Drive, Dropbox, OneDrive, S3, B2, Mega, pCloud, …) through the bundled rclone.
 * cfg.host = remote name from rclone.conf, cfg.path = folder inside the remote.
 */
class RcloneEndpoint(@Suppress("unused") private val ctx: Context, private val cfg: EndpointConfig) : Endpoint {
    private val remote = cfg.host.trim()
    private val fs = "$remote:"
    private val base = PathUtil.join(cfg.path)
    private val madeDirs = HashSet<String>()

    private val localRoot = remote == ":local"
    private fun rp(rel: String): String = (if (localRoot) "/" else "") + PathUtil.join(base, rel)
    private fun params(remotePath: String) = JSONObject().put("fs", fs).put("remote", remotePath)

    override fun checkAvailable() {
        val key = "$remote/$base"
        val now = System.currentTimeMillis()
        availability[key]?.let { (time, err) ->
            if (now - time < (if (err == null) 180_000 else 45_000)) { if (err != null) throw IOException(err) else return }
        }
        try {
            if (remote.isEmpty()) throw IOException("No rclone remote selected")
            if (!RcloneManager.isAvailable) throw IOException("rclone is not included in this build")
            if (!remote.startsWith(":") && RcloneManager.listRemotes().none { it.name == remote }) throw IOException("rclone remote “$remote” not found – add or import it in rclone remotes")
            try {
                RcloneDaemon.call("operations/list", params(base).put("opt", JSONObject().put("noMimeType", true).put("maxDepth", 1)))
            } catch (e: IOException) {
                // A not-yet-existing folder is fine (it is created on first upload)
                if (!isNotFound(e)) throw e
            }
            availability[key] = now to null
        } catch (e: IOException) {
            availability[key] = now to (e.message ?: "rclone error")
            throw e
        }
    }

    private fun isNotFound(e: Exception) = (e.message ?: "").let { it.contains("not found", true) || it.contains("doesn't exist", true) }

    override fun list(dir: String): List<RemoteFile> {
        val res = try {
            RcloneDaemon.call("operations/list", params(rp(dir)).put("opt", JSONObject().put("noMimeType", true)))
        } catch (e: IOException) {
            if (isNotFound(e)) return emptyList() else throw e
        }
        val arr = res.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<RemoteFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val isDir = o.optBoolean("IsDir")
            val mod = try { OffsetDateTime.parse(o.optString("ModTime")).toInstant().toEpochMilli() } catch (e: Exception) { 0L }
            out += RemoteFile(o.getString("Name"), isDir, if (isDir) 0L else o.optLong("Size", 0L), mod)
        }
        return out
    }

    override fun read(path: String): InputStream {
        val c = RcloneDaemon.ensure()
        val b = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(c.port).addPathSegment(if (localRoot) "[:local:/]" else "[$fs]")
        for (s in PathUtil.segments(base, path)) b.addPathSegment(s)
        RcloneDaemon.begin()
        try {
            val r = RcloneDaemon.http.newCall(Request.Builder().url(b.build()).header("Authorization", c.auth).get().build()).execute()
            if (!r.isSuccessful) {
                val code = r.code; r.close()
                throw IOException("rclone download failed: HTTP $code")
            }
            val ins = r.body!!.byteStream()
            return object : FilterInputStream(ins) {
                private var closed = false
                override fun close() {
                    try { super.close() } finally { r.close(); if (!closed) { closed = true; RcloneDaemon.end() } }
                }
            }
        } catch (e: Exception) { RcloneDaemon.end(); throw e }
    }

    private class StreamBody(val input: InputStream, val size: Long) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { input.source().use { sink.writeAll(it) } }
    }

    override fun write(path: String, input: InputStream, size: Long, modified: Long) {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        mkdirs(dir)
        val c = RcloneDaemon.ensure()
        val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(c.port)
            .addPathSegments("operations/uploadfile")
            .addQueryParameter("fs", fs).addQueryParameter("remote", rp(dir)).build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, StreamBody(input, size)).build()
        RcloneDaemon.begin()
        try {
            RcloneDaemon.http.newCall(Request.Builder().url(url).header("Authorization", c.auth).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) {
                    val text = r.body?.string().orEmpty()
                    val msg = try { JSONObject(text).optString("error", text) } catch (e: Exception) { text }
                    throw IOException("rclone upload failed: ${msg.ifBlank { "HTTP ${r.code}" }}")
                }
            }
        } finally { RcloneDaemon.end() }
    }

    override fun delete(path: String, isDir: Boolean) {
        RcloneDaemon.call(if (isDir) "operations/rmdir" else "operations/deletefile", params(rp(path)))
        madeDirs.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun mkdirs(dir: String) {
        val p = rp(dir)
        if (p.isEmpty() || p in madeDirs) return
        RcloneDaemon.call("operations/mkdir", params(p))
        madeDirs += p
    }

    override fun close() { madeDirs.clear() }

    companion object {
        /** remote/base -> (time, error or null): avoids hitting cloud APIs on every 45 s indicator refresh. */
        private val availability = ConcurrentHashMap<String, Pair<Long, String?>>()
    }
}
