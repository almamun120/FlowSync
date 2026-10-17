package app.flowsync.rclone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteInfo(val name: String, val type: String)

data class BackendOption(
    val name: String,
    val help: String,
    val default: String,
    val required: Boolean,
    val isPassword: Boolean,
    val advanced: Boolean,
    val provider: String,
    val examples: List<Pair<String, String>>,
    val exclusive: Boolean
)

data class Backend(val name: String, val description: String, val options: List<BackendOption>) {
    /** OAuth backends (Google Drive, Dropbox, OneDrive, Box …) are set up through the browser flow. */
    val isOAuth: Boolean get() = options.any { it.name == "token" } && options.any { it.name == "client_id" }
}

/** Paths, process helpers and config handling for the bundled rclone binary (no root needed). */
object RcloneManager {
    private lateinit var ctx: Context
    fun init(c: Context) { ctx = c.applicationContext; home.mkdirs() }

    /** Shipped as jniLibs/<abi>/librclone.so – Android extracts it to the native library dir where it may be executed. */
    val binary: File get() = File(ctx.applicationInfo.nativeLibraryDir, "librclone.so")
    val isAvailable: Boolean get() = binary.exists()
    val home: File get() = File(ctx.filesDir, "rclone")
    val configFile: File get() = File(home, "rclone.conf")

    fun builder(vararg args: String): ProcessBuilder {
        if (!isAvailable) throw IOException("rclone is not included in this build (see README: build-rclone step)")
        home.mkdirs()
        val cmd = ArrayList<String>().apply { add(binary.absolutePath); addAll(args) }
        val pb = ProcessBuilder(cmd)
        pb.environment().apply {
            put("HOME", home.absolutePath)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("XDG_CACHE_HOME", ctx.cacheDir.absolutePath)
            put("RCLONE_CONFIG", configFile.absolutePath)
            put("RCLONE_CACHE_DIR", File(ctx.cacheDir, "rclone").absolutePath)
        }
        pb.directory(home)
        return pb
    }

    class CmdResult(val code: Int, val out: String, val err: String)

    /** Runs rclone to completion and captures stdout / stderr separately. */
    fun run(vararg args: String, timeoutSec: Long = 60): CmdResult {
        val p = builder(*args).start()
        p.outputStream.close()
        var err = ""
        val t = Thread { err = p.errorStream.bufferedReader().readText() }.also { it.start() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("rclone timed out") }
        t.join(2000)
        return CmdResult(p.exitValue(), out, err)
    }

    fun version(): String? = try {
        run("version", timeoutSec = 15).out.lineSequence().firstOrNull { it.isNotBlank() }
    } catch (e: Exception) { null }

    // ---------------- remotes ----------------

    fun listRemotes(): List<RemoteInfo> {
        if (!isAvailable || !configFile.exists()) return emptyList()
        // Parse the ini directly: instant, and no process start needed.
        return RcloneConf.read(configFile).map { (n, kv) -> RemoteInfo(n, kv["type"] ?: "?") }
    }

    fun deleteRemote(name: String) {
        val conf = RcloneConf.read(configFile)
        conf.remove(name)
        RcloneConf.write(configFile, conf)
    }

    fun validName(n: String) = n.isNotBlank() && !n.startsWith("-") && n.all { it.isLetterOrDigit() || it in "_.+@- " } && !n.endsWith(" ")

    // ---------------- backends (from `rclone config providers`) ----------------

    @Volatile private var cachedBackends: List<Backend>? = null

    fun backends(): List<Backend> {
        cachedBackends?.let { return it }
        val r = run("config", "providers", timeoutSec = 60)
        if (r.code != 0) throw IOException("rclone config providers failed: ${r.err.take(300)}")
        val arr = JSONArray(r.out)
        val list = ArrayList<Backend>()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val opts = ArrayList<BackendOption>()
            val oa = b.optJSONArray("Options") ?: JSONArray()
            for (j in 0 until oa.length()) {
                val o = oa.getJSONObject(j)
                if (o.optInt("Hide", 0) != 0) continue
                val ex = ArrayList<Pair<String, String>>()
                val ea = o.optJSONArray("Examples")
                if (ea != null) for (k in 0 until ea.length()) {
                    val e = ea.getJSONObject(k); ex += e.optString("Value") to e.optString("Help")
                }
                opts += BackendOption(
                    name = o.getString("Name"),
                    help = o.optString("Help").lineSequence().firstOrNull().orEmpty(),
                    default = o.optString("DefaultStr", ""),
                    required = o.optBoolean("Required"),
                    isPassword = o.optBoolean("IsPassword"),
                    advanced = o.optBoolean("Advanced"),
                    provider = o.optString("Provider"),
                    examples = ex,
                    exclusive = o.optBoolean("Exclusive")
                )
            }
            list += Backend(b.getString("Name"), b.optString("Description"), opts)
        }
        list.sortBy { it.description.lowercase() }
        cachedBackends = list
        return list
    }

    /** Creates a remote for non-OAuth backends through rclone itself (it obscures passwords). */
    fun createRemote(name: String, type: String, params: Map<String, String>): String {
        val args = ArrayList<String>(listOf("config", "create", name, type))
        for ((k, v) in params) if (v.isNotEmpty()) { args += k; args += v }
        args += "--obscure"; args += "--non-interactive"
        val r = run(*args.toTypedArray(), timeoutSec = 60)
        if (r.code != 0) throw IOException(r.err.trim().ifEmpty { r.out.trim() }.take(400))
        if (listRemotes().none { it.name == name }) throw IOException("rclone did not save the remote: ${r.out.take(300)}")
        return "Remote “$name” created"
    }

    // ---------------- OAuth (Google Drive, Dropbox, OneDrive, …) ----------------

    /**
     * Runs `rclone authorize <type>`. rclone starts a tiny web server on 127.0.0.1:53682 (this phone),
     * we open the sign-in URL in the browser, Google/… redirects back to it, and rclone prints the token.
     */
    class AuthJob(private val proc: Process) {
        fun cancel() { proc.destroyForcibly() }
    }

    fun authorize(
        type: String, clientId: String, clientSecret: String,
        onUrl: (String) -> Unit,
        onJob: (AuthJob) -> Unit
    ): String {
        val args = ArrayList<String>(listOf("authorize", type))
        if (clientId.isNotBlank()) { args += clientId.trim(); args += clientSecret.trim() }
        args += "--auth-no-open-browser"
        val p = builder(*args.toTypedArray()).redirectErrorStream(true).start()
        p.outputStream.close()
        onJob(AuthJob(p))
        val urlRe = Regex("http://127\\.0\\.0\\.1:\\d+/auth\\?state=[A-Za-z0-9_\\-]+")
        var urlSent = false
        var token: String? = null
        var nextIsToken = false
        val log = StringBuilder()
        val deadline = System.currentTimeMillis() + 5 * 60_000
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (log.length < 4000) log.appendLine(line)
                if (!urlSent) urlRe.find(line)?.let { urlSent = true; onUrl(it.value) }
                if (nextIsToken && line.trim().startsWith("{")) { token = line.trim(); break }
                if (line.contains("Paste the following into your remote machine")) nextIsToken = true
                if (System.currentTimeMillis() > deadline) break
            }
        }
        p.destroyForcibly()
        return token ?: throw IOException("Sign-in was not completed.\n" + log.toString().takeLast(500))
    }

    fun saveOAuthRemote(name: String, type: String, token: String, clientId: String, clientSecret: String) {
        val conf = RcloneConf.read(configFile)
        val kv = LinkedHashMap<String, String>()
        kv["type"] = type
        if (type == "drive") kv["scope"] = "drive"
        if (clientId.isNotBlank()) { kv["client_id"] = clientId.trim(); kv["client_secret"] = clientSecret.trim() }
        kv["token"] = token
        conf[name] = kv
        RcloneConf.write(configFile, conf)
    }

    // ---------------- import / export of rclone.conf ----------------

    fun importConf(text: String, replace: Boolean) {
        val incoming = RcloneConf.parse(text)
        if (incoming.isEmpty()) throw IOException("No remotes found in that file")
        val conf: LinkedHashMap<String, LinkedHashMap<String, String>> = if (replace) LinkedHashMap() else RcloneConf.read(configFile)
        conf.putAll(incoming)
        RcloneConf.write(configFile, conf)
    }

    fun exportConf(): String = if (configFile.exists()) configFile.readText() else ""
}

/** Minimal rclone.conf (ini) reader/writer. */
object RcloneConf {
    fun read(f: File): LinkedHashMap<String, LinkedHashMap<String, String>> =
        if (f.exists()) parse(f.readText()) else LinkedHashMap()

    fun parse(text: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var cur: LinkedHashMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue
            if (l.startsWith("[") && l.endsWith("]")) {
                cur = LinkedHashMap(); out[l.substring(1, l.length - 1)] = cur
            } else if (cur != null) {
                val i = l.indexOf('=')
                if (i > 0) cur[l.substring(0, i).trim()] = l.substring(i + 1).trim()
            }
        }
        return out
    }

    fun write(f: File, conf: Map<String, Map<String, String>>) {
        f.parentFile?.mkdirs()
        val sb = StringBuilder()
        for ((n, kv) in conf) {
            sb.append('[').append(n).append("]\n")
            for ((k, v) in kv) sb.append(k).append(" = ").append(v).append('\n')
            sb.append('\n')
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
}
