package app.flowsync.data

import app.flowsync.model.SyncProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.flowsync.model.AppConfig
import java.io.File

/** JSON-file backed profile store (app-private storage). */
class ProfileRepository(private val dir: File) {
    private val file = File(dir, "profiles.json")
    private val _profiles = MutableStateFlow<List<SyncProfile>>(emptyList())
    val profiles: StateFlow<List<SyncProfile>> = _profiles.asStateFlow()

    init {
        dir.mkdirs()
        try {
            if (file.exists()) _profiles.value = BackupManager.parse(file.readText()).profiles
        } catch (e: Exception) {
            try { file.copyTo(File(dir, "profiles.corrupt.json"), overwrite = true) } catch (_: Exception) {}
        }
    }

    fun get(id: String): SyncProfile? = _profiles.value.firstOrNull { it.id == id }

    @Synchronized
    private fun mutate(transform: (List<SyncProfile>) -> List<SyncProfile>) {
        val next = transform(_profiles.value)
        val tmp = File(dir, "profiles.json.tmp")
        tmp.writeText(BackupManager.json.encodeToString(AppConfig.serializer(), AppConfig(exportedAt = System.currentTimeMillis(), profiles = next)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        _profiles.value = next
    }

    fun upsert(p: SyncProfile) = mutate { list ->
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun updateLastRun(id: String, time: Long, result: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(lastRunAt = time, lastResult = result) else it }
    }

    fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    fun replaceAll(newList: List<SyncProfile>) = mutate { newList }

    fun merge(incoming: List<SyncProfile>) = mutate { list ->
        val ids = incoming.map { it.id }.toSet()
        list.filterNot { it.id in ids } + incoming
    }
}
