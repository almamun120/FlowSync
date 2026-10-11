package app.flowsync.fs

import android.accounts.Account
import android.content.Context
import app.flowsync.model.EndpointConfig
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Google authorization (Play Services). Interactive consent happens once in the profile editor. */
object GDriveAuth {
    fun scope(full: Boolean) =
        if (full) "https://www.googleapis.com/auth/drive" else "https://www.googleapis.com/auth/drive.file"

    fun request(full: Boolean, email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(scope(full))))
        if (!email.isNullOrBlank()) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    /** Blocking, background-safe. Fails if the user has to sign in again. */
    fun token(ctx: Context, email: String, full: Boolean): String {
        if (email.isBlank()) throw IOException("Google Drive: not signed in")
        try {
            val res = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request(full, email)), 30, TimeUnit.SECONDS)
            if (res.hasResolution()) throw IOException("Google sign-in expired – open the profile and sign in again")
            return res.accessToken ?: throw IOException("Google returned no access token")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Google sign-in failed: ${e.cause?.message ?: e.message}")
        }
    }
}

/** Google Drive REST v3 client. Paths are folder names under My Drive (or under the configured base folder). */
class GDriveEndpoint(private val ctx: Context, private val cfg: EndpointConfig) : Endpoint {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var token: String? = null
    private var tokenAt = 0L
    private val ids = HashMap<String, String>() // full path (base + rel) -> Drive file id
    private val base = PathUtil.join(cfg.path)

    private fun key(rel: String) = PathUtil.join(base, rel)

    private fun exec(rb: Request.Builder): Response {
        val now = System.currentTimeMillis()
        if (token == null || now - tokenAt > 40 * 60 * 1000L) {
            token = GDriveAuth.token(ctx, cfg.username, cfg.driveFull)
            tokenAt = now
        }
        return http.newCall(rb.header("Authorization", "Bearer $token").build()).execute()
    }

    private fun Response.check(what: String): Response {
        if (!isSuccessful) {
            val detail = try {
                JSONObject(body?.string() ?: "").getJSONObject("error").optString("message")
            } catch (e: Exception) { "" }
            val status = code
            close()
            throw IOException("Drive $what failed: HTTP $status $detail".trim())
        }
        return this
    }

    private fun filesUrl(): HttpUrl.Builder =
        HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/files")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    private fun parseIso(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time ?: 0L
    } catch (e: Exception) { 0L }

    override fun checkAvailable() {
        val url = HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/about")
            .addQueryParameter("fields", "user").build()
        exec(Request.Builder().url(url).get()).check("connect").close()
    }

    /** Single targeted lookup (used for path resolution). */
    private fun findChild(parentId: String, name: String): Pair<String, Boolean>? {
        val url = filesUrl()
            .addQueryParameter("q", "name = '${esc(name)}' and '$parentId' in parents and trashed = false")
            .addQueryParameter("fields", "files(id,mimeType)")
            .addQueryParameter("pageSize", "5").build()
        return exec(Request.Builder().url(url).get()).check("lookup").use { r ->
            val arr = JSONObject(r.body!!.string()).optJSONArray("files")
            if (arr == null || arr.length() == 0) return@use null
            var pick = arr.getJSONObject(0)
            for (i in 0 until arr.length()) { // prefer folders
                val o = arr.getJSONObject(i)
                if (o.optString("mimeType") == FOLDER) { pick = o; break }
            }
            pick.getString("id") to (pick.optString("mimeType") == FOLDER)
        }
    }

    private fun resolveKey(k: String): String? {
        if (k.isEmpty()) return "root"
        ids[k]?.let { return it }
        val parent = resolveKey(k.substringBeforeLast('/', "")) ?: return null
        val found = findChild(parent, k.substringAfterLast('/')) ?: return null
        ids[k] = found.first
        return found.first
    }

    override fun list(dir: String): List<RemoteFile> {
        val k = key(dir)
        val id = resolveKey(k) ?: return emptyList()
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val b = filesUrl()
                .addQueryParameter("q", "'$id' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                .addQueryParameter("pageSize", "1000")
            if (page != null) b.addQueryParameter("pageToken", page)
            exec(Request.Builder().url(b.build()).get()).check("list").use { r ->
                val o = JSONObject(r.body!!.string())
                val arr: JSONArray = o.optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val mime = f.optString("mimeType")
                    val isDir = mime == FOLDER
                    if (!isDir && mime.startsWith("application/vnd.google-apps.")) continue // Docs/Sheets/shortcuts
                    val name = f.getString("name")
                    ids[PathUtil.join(k, name)] = f.getString("id")
                    out += RemoteFile(name, isDir, f.optString("size", "0").toLongOrNull() ?: 0L, parseIso(f.optString("modifiedTime")))
                }
                page = if (o.has("nextPageToken")) o.getString("nextPageToken") else null
            }
        } while (page != null)
        return out
    }

    override fun read(path: String): InputStream {
        val id = resolveKey(key(path)) ?: throw IOException("File not found: $path")
        val url = filesUrl().addPathSegment(id).addQueryParameter("alt", "media").build()
        val r = exec(Request.Builder().url(url).get()).check("download")
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
        val parentRel = path.substringBeforeLast('/', "")
        mkdirs(parentRel)
        val k = key(path)
        val existing = resolveKey(k)
        val meta = JSONObject()
        if (existing == null) {
            meta.put("name", path.substringAfterLast('/'))
            meta.put("parents", JSONArray().put(resolveKey(key(parentRel)) ?: throw IOException("Parent folder missing")))
        }
        if (modified > 0) meta.put("modifiedTime", iso(modified))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(StreamBody(input, size))
            .build()
        val ub = HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("upload/drive/v3/files")
        if (existing != null) ub.addPathSegment(existing)
        ub.addQueryParameter("uploadType", "multipart").addQueryParameter("fields", "id")
        val rb = Request.Builder().url(ub.build())
        if (existing != null) rb.patch(body) else rb.post(body)
        exec(rb).check("upload").use { r ->
            ids[k] = JSONObject(r.body!!.string()).getString("id")
        }
    }

    override fun delete(path: String, isDir: Boolean) {
        val k = key(path)
        val id = resolveKey(k) ?: return
        val url = filesUrl().addPathSegment(id).build()
        // Move to Drive trash (recoverable) instead of deleting permanently.
        val body = JSONObject().put("trashed", true).toString().toRequestBody("application/json".toMediaType())
        exec(Request.Builder().url(url).patch(body)).check("delete").close()
        ids.keys.removeAll { it == k || it.startsWith("$k/") }
    }

    override fun mkdirs(dir: String) {
        var cur = ""
        for (seg in PathUtil.segments(base, dir)) {
            val parentKey = cur
            cur = if (cur.isEmpty()) seg else "$cur/$seg"
            if (resolveKey(cur) != null) continue
            val pid = resolveKey(parentKey) ?: throw IOException("Parent folder missing")
            val meta = JSONObject().put("name", seg).put("mimeType", FOLDER).put("parents", JSONArray().put(pid))
            val url = filesUrl().addQueryParameter("fields", "id").build()
            exec(Request.Builder().url(url).post(meta.toString().toRequestBody("application/json".toMediaType())))
                .check("create folder").use { r -> ids[cur] = JSONObject(r.body!!.string()).getString("id") }
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        ids.clear()
    }

    companion object {
        private const val FOLDER = "application/vnd.google-apps.folder"

        /** E-mail of the account owning [token] (used right after sign-in). */
        fun fetchEmail(token: String): String? = try {
            val c = OkHttpClient()
            val url = HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/about")
                .addQueryParameter("fields", "user(emailAddress)").build()
            c.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()).execute().use { r ->
                if (!r.isSuccessful) null
                else JSONObject(r.body!!.string()).getJSONObject("user").getString("emailAddress")
            }
        } catch (e: Exception) { null }
    }
}
