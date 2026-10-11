package app.flowsync.sync

import app.flowsync.fs.RemoteFile
import app.flowsync.model.FileFilter
import app.flowsync.model.FilterMode

object Extensions {
    fun normalize(s: String): String = s.trim().lowercase().removePrefix("*").removePrefix(".")

    /** "jpg, .PNG; *.mp4" -> [jpg, png, mp4] */
    fun parse(text: String): List<String> =
        text.split(Regex("[,;\\s]+")).map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

    val presets: List<Pair<String, List<String>>> = listOf(
        "Images" to listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"),
        "Videos" to listOf("mp4", "mkv", "mov", "avi", "3gp", "webm"),
        "Audio" to listOf("mp3", "m4a", "flac", "wav", "ogg", "aac"),
        "Documents" to listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt"),
        "Archives" to listOf("zip", "rar", "7z", "tar", "gz")
    )
}

/** Custom-extension based file filter. */
class FileMatcher(private val f: FileFilter) {
    private val exts: Set<String> = f.extensions.map { Extensions.normalize(it) }.filter { it.isNotEmpty() }.toSet()

    fun accepts(file: RemoteFile): Boolean {
        if (f.maxSizeMb > 0 && file.size > f.maxSizeMb * 1024L * 1024L) return false
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return when (f.mode) {
            FilterMode.ALL -> true
            FilterMode.ONLY -> ext in exts
            FilterMode.EXCLUDE -> ext !in exts
        }
    }
}
