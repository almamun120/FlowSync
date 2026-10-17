@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.flowsync.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flowsync.FlowSyncApp
import app.flowsync.data.LogStore
import app.flowsync.model.RcloneTask
import app.flowsync.model.ServeConfig
import app.flowsync.rclone.RcloneManager
import app.flowsync.rclone.ServeCenter
import app.flowsync.util.AppPermissions
import app.flowsync.work.Scheduler
import app.flowsync.work.SyncCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
private fun TextViewerDialog(title: String, provider: () -> String, onClose: () -> Unit) {
    var text by remember { mutableStateOf(provider()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text.ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { TextButton(onClick = { text = provider() }) { Text("Refresh") } }
    )
}

// ============================================================ SERVERS

@Composable
fun ServersView(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as FlowSyncApp).rcloneStore
    val serves by store.serves.collectAsStateWithLifecycle()
    val states by ServeCenter.states.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ServeConfig?>(null) }
    var logFor by remember { mutableStateOf<ServeConfig?>(null) }
    var deleting by remember { mutableStateOf<ServeConfig?>(null) }

    val ed = editing
    if (ed != null) {
        ServeEditor(ed, onBack = { editing = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("rclone servers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = ServeConfig(name = "", kind = "http", port = 8080) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New server") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Turn any remote or folder into a server on your network: HTTP, WebDAV, FTP, SFTP, DLNA, S3, NFS – or open rclone's full Web GUI. " +
                        "Switch a server on/off here; it keeps running in the background with a notification.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (serves.isEmpty()) item { Text("No servers yet.", style = MaterialTheme.typography.bodyMedium) }
            items(serves, key = { it.id }) { s ->
                val st = states[s.id]
                val running = st?.running == true
                var menu by remember { mutableStateOf(false) }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (running) app.flowsync.model.DotStatus.ONLINE else app.flowsync.model.DotStatus.OFFLINE)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name.ifBlank { s.kind }, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (ServeCenter.KINDS.firstOrNull { it.id == s.kind }?.label ?: s.kind) +
                                        if (s.kind != "webgui") "  ·  ${s.target}" else "",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(checked = running, onCheckedChange = { on ->
                                if (on) ServeCenter.start(ctx, s.id) else ServeCenter.stop(s.id)
                            })
                            Column {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = s })
                                    DropdownMenuItem(text = { Text("View log") }, onClick = { menu = false; logFor = s })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = s })
                                }
                            }
                        }
                        if (running) {
                            st?.urls?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = OnlineGreen) }
                            val first = st?.urls?.firstOrNull()
                            if (first != null && first.startsWith("http")) {
                                FilledTonalButton(onClick = {
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(first)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                                }) { Text("Open in browser") }
                            }
                            if (s.user.isNotBlank()) Text("Login: ${s.user}", style = MaterialTheme.typography.bodySmall)
                        } else if (!st?.error.isNullOrBlank()) {
                            Text(st!!.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    logFor?.let { s -> TextViewerDialog("Log · ${s.name.ifBlank { s.kind }}", { ServeCenter.log(s.id) }) { logFor = null } }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete server?") },
            text = { Text("“${s.name.ifBlank { s.kind }}” will be stopped and removed.") },
            confirmButton = { TextButton(onClick = { ServeCenter.stop(s.id); store.deleteServe(s.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ServeEditor(initial: ServeConfig, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as FlowSyncApp).rcloneStore
    var c by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val remotes by produceState(emptyList<app.flowsync.rclone.RemoteInfo>()) { value = withContext(Dispatchers.IO) { RcloneManager.listRemotes() } }
    val kind = ServeCenter.KINDS.firstOrNull { it.id == c.kind } ?: ServeCenter.KINDS[0]
    val running = ServeCenter.isRunning(c.id)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (store.getServe(c.id) == null) "New server" else "Edit server") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (running) Text("This server is running – stop it and start it again for changes to apply.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Field("Name", c.name, { c = c.copy(name = it) })
            Text("Type", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServeCenter.KINDS.forEach { k ->
                    FilterChip(selected = c.kind == k.id, label = { Text(k.label) }, onClick = {
                        val oldDefault = kind.defaultPort
                        c = c.copy(kind = k.id, port = if (c.port == oldDefault) k.defaultPort else c.port)
                    })
                }
            }
            if (c.kind != "webgui") {
                Field("What to share (remote:path or phone folder)", c.target, { c = c.copy(target = it) }, placeholder = "mydrive:Photos   or   /storage/emulated/0/DCIM")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, label = { Text("Phone storage") }, onClick = { c = c.copy(target = "/storage/emulated/0") })
                    remotes.forEach { r -> FilterChip(selected = false, label = { Text("${r.name}:") }, onClick = { c = c.copy(target = r.name + ":") }) }
                }
                if (c.target.startsWith("/") && !AppPermissions.allFilesGranted()) {
                    Text("Sharing phone folders needs “All files access”.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { AppPermissions.requestAllFiles(ctx) }) { Text("Grant All files access") }
                }
            } else {
                Text("Opens rclone's own web interface to manage every remote, job and setting. Keep the login enabled if the network is shared.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Field("Port (1024–65535)", c.port.toString(), { c = c.copy(port = it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0) }, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reachable from Wi-Fi network")
                    Text("Off = only apps on this phone (127.0.0.1)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(c.lan, { c = c.copy(lan = it) })
            }
            if (c.kind != "dlna" && c.kind != "nfs") {
                Field("Username (empty = no login)", c.user, { c = c.copy(user = it) })
                Field("Password", c.pass, { c = c.copy(pass = it) }, password = true)
            }
            if (c.kind != "webgui" && c.kind != "dlna") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read-only", Modifier.weight(1f))
                    Switch(c.readOnly, { c = c.copy(readOnly = it) })
                }
            }
            Field("Extra rclone flags (optional)", c.extraFlags, { c = c.copy(extraFlags = it) }, placeholder = "--dir-cache-time 5m --vfs-cache-mode full", singleLine = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start automatically after reboot")
                }
                Switch(c.autoStart, { c = c.copy(autoStart = it) })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                when {
                    c.name.isBlank() -> error = "Give the server a name."
                    c.kind != "webgui" && c.target.isBlank() -> error = "Choose what to share."
                    c.port !in 1024..65535 -> error = "Port must be between 1024 and 65535."
                    else -> { store.upsertServe(c); onBack() }
                }
            }) { Text("Save") }
        }
    }
}

// ============================================================ SAVED COMMANDS

private val TEMPLATES = listOf(
    "lsd remote:" to "lsd remote:",
    "about" to "about remote:",
    "sync" to "sync src: dst: --progress",
    "check" to "check src: dst:",
    "dedupe" to "dedupe --dedupe-mode newest remote:",
    "cleanup" to "cleanup remote:",
    "size" to "size remote:",
    "delete old" to "delete remote:folder --min-age 30d"
)

@Composable
fun TasksView(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as FlowSyncApp).rcloneStore
    val tasks by store.tasks.collectAsStateWithLifecycle()
    val runs by SyncCenter.states.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RcloneTask?>(null) }
    var logFor by remember { mutableStateOf<RcloneTask?>(null) }
    var deleting by remember { mutableStateOf<RcloneTask?>(null) }

    val ed = editing
    if (ed != null) { TaskEditor(ed, onBack = { editing = null }); return }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved rclone commands") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = RcloneTask() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New command") })
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Save any rclone command with all its flags and run it now or on a schedule in the background – " +
                        "sync, copy, check, dedupe, cleanup, delete old files, backends commands and more.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (tasks.isEmpty()) item { Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium) }
            items(tasks, key = { it.id }) { t ->
                val run = runs[t.id]
                var menu by remember { mutableStateOf(false) }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                                Text("rclone ${t.command}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Column {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = t })
                                    DropdownMenuItem(text = { Text("View log") }, onClick = { menu = false; logFor = t })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = t })
                                }
                            }
                        }
                        if (t.intervalMinutes > 0) Tag("every " + intervalLabel(t.intervalMinutes))
                        if (run != null) Text(run.message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (t.lastRunAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.lastRunAt)) + "\n" + t.lastResult
                                else "Never run",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (run != null) FilledTonalButton(onClick = { SyncCenter.requestStop(t.id) }) { Text("Stop") }
                            else Button(onClick = { Scheduler.runTaskNow(ctx, t.id) }) { Text("Run now") }
                        }
                    }
                }
            }
        }
    }
    logFor?.let { t -> TextViewerDialog("Log · ${t.name}", { LogStore.read(t.id, 400).joinToString("\n") }) { logFor = null } }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete command?") },
            text = { Text("“${t.name}” and its schedule will be removed.") },
            confirmButton = { TextButton(onClick = { Scheduler.cancelTask(ctx, t.id); store.deleteTask(t.id); LogStore.clear(t.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TaskEditor(initial: RcloneTask, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as FlowSyncApp).rcloneStore
    var t by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (store.getTask(t.id) == null) "New command" else "Edit command") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("Name", t.name, { t = t.copy(name = it) })
            Field("rclone command (without the word rclone)", t.command, { t = t.copy(command = it) },
                placeholder = "sync mydrive:Photos dropbox:Backup --transfers 8", singleLine = false)
            Text("Templates", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TEMPLATES.forEach { (label, cmd) -> FilterChip(selected = false, label = { Text(label) }, onClick = { t = t.copy(command = cmd) }) }
            }
            Text("Run automatically", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60, 180, 360, 720, 1440).forEach { m ->
                    FilterChip(selected = t.intervalMinutes == m, label = { Text(if (m == 0) "Manual" else intervalLabel(m)) },
                        onClick = { t = t.copy(intervalMinutes = m) })
                }
            }
            if (t.intervalMinutes > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi / unmetered only", Modifier.weight(1f))
                    Switch(t.wifiOnly, { t = t.copy(wifiOnly = it) })
                }
            }
            Text("Commands run without a keyboard: use flags such as --dry-run or -y instead of interactive prompts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                when {
                    t.name.isBlank() -> error = "Give it a name."
                    t.command.isBlank() -> error = "Enter a command."
                    else -> { store.upsertTask(t); Scheduler.applyTask(ctx, t); onBack() }
                }
            }) { Text("Save") }
        }
    }
}
