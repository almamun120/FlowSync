package app.flowsync.data

import app.flowsync.model.RcloneTask
import app.flowsync.model.ServeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class Extras(val serves: List<ServeConfig> = emptyList(), val tasks: List<RcloneTask> = emptyList())

/** JSON store for rclone servers and saved commands. */
class RcloneStore(private val dir: File) {
    private val file = File(dir, "rclone_extras.json")
    private val _serves = MutableStateFlow<List<ServeConfig>>(emptyList())
    private val _tasks = MutableStateFlow<List<RcloneTask>>(emptyList())
    val serves: StateFlow<List<ServeConfig>> = _serves.asStateFlow()
    val tasks: StateFlow<List<RcloneTask>> = _tasks.asStateFlow()

    init {
        try {
            if (file.exists()) {
                val e = BackupManager.json.decodeFromString(Extras.serializer(), file.readText())
                _serves.value = e.serves; _tasks.value = e.tasks
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun persist(s: List<ServeConfig>, t: List<RcloneTask>) {
        val tmp = File(dir, "rclone_extras.json.tmp")
        tmp.writeText(BackupManager.json.encodeToString(Extras.serializer(), Extras(s, t)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        _serves.value = s; _tasks.value = t
    }

    fun getServe(id: String) = _serves.value.firstOrNull { it.id == id }
    fun getTask(id: String) = _tasks.value.firstOrNull { it.id == id }

    fun upsertServe(x: ServeConfig) = persist(
        if (_serves.value.any { it.id == x.id }) _serves.value.map { if (it.id == x.id) x else it } else _serves.value + x, _tasks.value)
    fun deleteServe(id: String) = persist(_serves.value.filterNot { it.id == id }, _tasks.value)

    fun upsertTask(x: RcloneTask) = persist(_serves.value,
        if (_tasks.value.any { it.id == x.id }) _tasks.value.map { if (it.id == x.id) x else it } else _tasks.value + x)
    fun deleteTask(id: String) = persist(_serves.value, _tasks.value.filterNot { it.id == id })
    fun updateTaskRun(id: String, time: Long, result: String) = persist(_serves.value,
        _tasks.value.map { if (it.id == id) it.copy(lastRunAt = time, lastResult = result) else it })

    fun merge(s: List<ServeConfig>, t: List<RcloneTask>) {
        val sid = s.map { it.id }.toSet(); val tid = t.map { it.id }.toSet()
        persist(_serves.value.filterNot { it.id in sid } + s, _tasks.value.filterNot { it.id in tid } + t)
    }
    fun replaceAll(s: List<ServeConfig>, t: List<RcloneTask>) = persist(s, t)
}
