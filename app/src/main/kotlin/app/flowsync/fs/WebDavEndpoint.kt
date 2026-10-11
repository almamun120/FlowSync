package app.flowsync.fs

import android.net.Uri
import android.util.Xml
import app.flowsync.model.EndpointConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WebDAV client (Nextcloud, ownCloud, Synology, nginx/Apache dav…) on top of OkHttp. Basic auth. */
class WebDavEndpoint(private val cfg: EndpointConfig) : Endpoint {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(maxOf(cfg.timeoutSec, 30).toLong(), TimeUnit.SECONDS)
        .writeTimeout(maxOf(cfg.timeoutSec, 30).toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            var r = chain.request()
            if (cfg.username.isNotBlank()) {
                r = r.newBuilder().header("Authorization", Credentials.basic(cfg.username, cfg.password, Charsets.UTF_8)).build()
            }
            chain.proceed(r)
        }
        .build()

    private val made = HashSet<String>()

    private fun urlOf(rel: String, dir: Boolean): HttpUrl {
        val h = cfg.host.trim()
        if (h.isEmpty()) throw IOException("Host is empty")
        val b: HttpUrl.Builder = if (h.startsWith("http://") || h.startsWith("https://")) {
            h.toHttpUrl().newBuilder()
        } else {
            val port = if (cfg.port > 0) cfg.port else if (cfg.secure) 443 else 80
            HttpUrl.Builder().scheme(if (cfg.secure) "https" else "http").host(h).port(port)
        }
        for (s in PathUtil.segments(cfg.path, rel)) b.addPathSegment(s)
        if (dir) b.addPathSegment("")
        return b.build()
    }

    private fun urlForSegments(segs: List<String>): HttpUrl {
        val h = cfg.host.trim()
        val b: HttpUrl.Builder = if (h.startsWith("http://") || h.startsWith("https://")) h.toHttpUrl().newBuilder()
        else HttpUrl.Builder().scheme(if (cfg.secure) "https" else "http").host(h)
            .port(if (cfg.port > 0) cfg.port else if (cfg.secure) 443 else 80)
        for (s in segs) b.addPathSegment(s)
        b.addPathSegment("")
        return b.build()
    }

    private fun propfind(url: HttpUrl, depth: Int): Response {
        val body = PROPFIND_XML.toRequestBody("application/xml".toMediaType())
        return http.newCall(Request.Builder().url(url).method("PROPFIND", body).header("Depth", depth.toString()).build()).execute()
    }

    override fun checkAvailable() {
        propfind(urlOf("", true), 0).use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("Authentication failed (HTTP ${r.code})")
            if (r.code >= 500) throw IOException("Server error HTTP ${r.code}")
        }
    }

    override fun list(dir: String): List<RemoteFile> {
        val url = urlOf(dir, true)
        return propfind(url, 1).use { r ->
            if (r.code == 404) return@use emptyList<RemoteFile>()
            if (r.code != 207 && r.code != 200) throw IOException("HTTP ${r.code} ${r.message}")
            val self = Uri.decode(url.encodedPath).trim('/')
            parse(r.body!!.byteStream(), self)
        }
    }

    private fun parse(stream: InputStream, self: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(InputStreamReader(stream, Charsets.UTF_8))
        var href = ""; var isDir = false; var size = 0L; var mod = 0L
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "response" -> { href = ""; isDir = false; size = 0L; mod = 0L }
                    "href" -> if (href.isEmpty()) href = p.nextText().trim()
                    "collection" -> isDir = true
                    "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> mod = parseHttpDate(p.nextText().trim())
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "response") {
                val path = if (href.startsWith("http")) href.toHttpUrl().encodedPath else href
                val decoded = Uri.decode(path).trim('/')
                if (decoded != self && decoded.isNotEmpty()) {
                    out += RemoteFile(decoded.substringAfterLast('/'), isDir, size, mod)
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseHttpDate(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }

    override fun read(path: String): InputStream {
        val r = http.newCall(Request.Builder().url(urlOf(path, false)).get().build()).execute()
        if (!r.isSuccessful) { val c = r.code; r.close(); throw IOException("Download failed: HTTP $c") }
        val ins = r.body!!.byteStream()
        return object : FilterInputStream(ins) {
            override fun close() { try { super.close() } finally { r.close() } }
        }
    }

    private class StreamBody(val input: InputStream, val size: Long) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { input.source().use { sink.writeAll(it) } }
    }

    override fun write(path: String, input: InputStream, size: Long, modified: Long) {
        mkdirs(path.substringBeforeLast('/', ""))
        val rb = Request.Builder().url(urlOf(path, false)).put(StreamBody(input, if (size > 0) size else -1L))
        if (modified > 0) rb.header("X-OC-MTIME", (modified / 1000).toString()) // Nextcloud/ownCloud keep mtime
        http.newCall(rb.build()).execute().use { r ->
            if (r.code !in 200..299) throw IOException("Upload failed: HTTP ${r.code} ${r.message}")
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        http.newCall(Request.Builder().url(urlOf(path, isDir)).delete().build()).execute().use { r ->
            if (r.code !in 200..299 && r.code != 404) throw IOException("Delete failed: HTTP ${r.code}")
        }
    }

    override fun mkdirs(dir: String) {
        val segs = PathUtil.segments(cfg.path, dir)
        for (i in 1..segs.size) {
            val key = segs.subList(0, i).joinToString("/")
            if (key in made) continue
            val req = Request.Builder().url(urlForSegments(segs.subList(0, i))).method("MKCOL", null).build()
            http.newCall(req).execute().use { r ->
                // 201 created, 405 already exists
                if (r.code !in 200..299 && r.code != 405 && r.code != 301 && r.code != 302) {
                    throw IOException("Cannot create folder: HTTP ${r.code}")
                }
            }
            made += key
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        made.clear()
    }

    companion object {
        private const val PROPFIND_XML = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
