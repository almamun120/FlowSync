package app.flowsync.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class RunState(
    val message: String = "",
    val copied: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0
)

/** In-process run registry: UI progress + cooperative cancel + "already running" guard. */
object SyncCenter {
    private val _states = MutableStateFlow<Map<String, RunState>>(emptyMap())
    val states: StateFlow<Map<String, RunState>> = _states.asStateFlow()

    private val running = ConcurrentHashMap.newKeySet<String>()
    private val stops = ConcurrentHashMap.newKeySet<String>()

    fun tryStart(id: String): Boolean {
        val ok = running.add(id)
        if (ok) { stops.remove(id); _states.update { it + (id to RunState("Starting…")) } }
        return ok
    }

    fun finish(id: String) {
        running.remove(id); stops.remove(id)
        _states.update { it - id }
    }

    fun update(id: String, s: RunState) { _states.update { it + (id to s) } }
    fun requestStop(id: String) { if (id in running) stops.add(id) }
    fun isStopRequested(id: String) = id in stops
    fun isRunning(id: String) = id in running
}
