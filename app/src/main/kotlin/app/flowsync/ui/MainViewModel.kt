package app.flowsync.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flowsync.FlowSyncApp
import app.flowsync.data.BackupManager
import app.flowsync.data.LogStore
import app.flowsync.fs.Endpoints
import app.flowsync.fs.GDriveEndpoint
import app.flowsync.model.AppConfig
import app.flowsync.model.Availability
import app.flowsync.model.DotStatus
import app.flowsync.model.EndpointConfig
import app.flowsync.model.SyncProfile
import app.flowsync.work.Scheduler
import app.flowsync.work.SyncCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as FlowSyncApp).repo
    val profiles: StateFlow<List<SyncProfile>> = repo.profiles

    private val _availability = MutableStateFlow<Map<String, Availability>>(emptyMap())
    val availability: StateFlow<Map<String, Availability>> = _availability.asStateFlow()
    private val tick = MutableStateFlow(0)

    fun getProfile(id: String): SyncProfile? = repo.get(id)

    // ---------- destination availability (green / red indicator) ----------

    fun refresh() { tick.update { it + 1 } }

    /** Runs while the UI is visible: checks every destination now and then every 45 s (or on refresh()). */
    suspend fun monitorLoop() {
        while (true) {
            checkAll()
            val seen = tick.value
            withTimeoutOrNull(45_000) { tick.first { it != seen } }
        }
    }

    private suspend fun checkAll() = coroutineScope {
        val list = repo.profiles.value
        val ids = list.map { it.id }.toSet()
        _availability.update { cur -> cur.filterKeys { it in ids } }
        list.map { p ->
            async(Dispatchers.IO) {
                val a = if (!p.enabled) Availability(DotStatus.PAUSED, "Profile is paused") else probe(p.destination)
                _availability.update { it + (p.id to a) }
            }
        }.awaitAll()
    }

    private fun probe(cfg: EndpointConfig): Availability = try {
        Endpoints.create(app, cfg.copy(timeoutSec = 8), true).use { it.checkAvailable() }
        Availability(DotStatus.ONLINE)
    } catch (e: Exception) {
        Availability(DotStatus.OFFLINE, e.message ?: e.javaClass.simpleName)
    }

    suspend fun testEndpoint(cfg: EndpointConfig, write: Boolean): String = withContext(Dispatchers.IO) {
        try {
            Endpoints.create(app, cfg, write).use { ep ->
                ep.checkAvailable()
                val n = ep.list("").size
                "Connected ✓  ($n items in folder)"
            }
        } catch (e: Exception) {
            "✗ " + (e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun driveEmail(token: String): String? = withContext(Dispatchers.IO) { GDriveEndpoint.fetchEmail(token) }

    // ---------- profiles ----------

    fun save(p: SyncProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.upsert(p)
            Scheduler.apply(app, p)
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Scheduler.cancel(app, id)
            repo.delete(id)
            LogStore.clear(id)
        }
    }

    fun duplicate(id: String) {
        repo.get(id)?.let { save(it.copy(id = UUID.randomUUID().toString(), name = it.name + " (copy)", lastRunAt = 0, lastResult = "")) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.setEnabled(id, enabled)
            repo.get(id)?.let { Scheduler.apply(app, it) }
            refresh()
        }
    }

    fun runNow(id: String) {
        val p = repo.get(id) ?: return
        if (!SyncCenter.isRunning(id)) Scheduler.runNow(app, p)
    }

    fun stop(id: String) = SyncCenter.requestStop(id)

    fun readLog(id: String): List<String> = LogStore.read(id)
    fun clearLog(id: String) = LogStore.clear(id)

    // ---------- JSON config backup ----------

    suspend fun exportTo(uri: Uri, includeSecrets: Boolean): String = withContext(Dispatchers.IO) {
        try {
            val text = BackupManager.export(repo.profiles.value, includeSecrets)
            (app.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open file")).use { it.write(text.toByteArray()) }
            "Saved ${repo.profiles.value.size} profile(s)"
        } catch (e: Exception) { "Export failed: ${e.message}" }
    }

    suspend fun readConfig(uri: Uri): Result<AppConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot open file")
            BackupManager.parse(text)
        }
    }

    fun applyImport(cfg: AppConfig, replace: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val old = repo.profiles.value
            if (replace) { old.forEach { Scheduler.cancel(app, it.id) }; repo.replaceAll(cfg.profiles) } else repo.merge(cfg.profiles)
            Scheduler.applyAll(app, repo.profiles.value)
            refresh()
        }
    }
}
