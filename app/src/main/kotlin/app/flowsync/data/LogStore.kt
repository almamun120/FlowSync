package app.flowsync.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(base: File) { dir = File(base, "logs").also { it.mkdirs() } }

    private fun f(id: String) = File(dir, "$id.log")

    @Synchronized
    fun append(id: String, line: String) {
        try { f(id).appendText("${fmt.format(Date())}  $line\n") } catch (_: Exception) {}
    }

    /** Keep logs small: when above ~300 KB keep only the last 800 lines. */
    @Synchronized
    fun rotate(id: String) {
        try {
            val file = f(id)
            if (file.exists() && file.length() > 300_000) {
                file.writeText(file.readLines().takeLast(800).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun read(id: String, max: Int = 500): List<String> = try {
        if (f(id).exists()) f(id).readLines().takeLast(max) else emptyList()
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun clear(id: String) { try { f(id).delete() } catch (_: Exception) {} }
}
