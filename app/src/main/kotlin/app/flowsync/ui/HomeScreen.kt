@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.flowsync.ui

import android.text.format.DateUtils
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flowsync.model.Availability
import app.flowsync.model.DotStatus
import app.flowsync.model.FilterMode
import app.flowsync.model.SyncProfile
import app.flowsync.util.Format
import app.flowsync.work.RunState
import app.flowsync.work.SyncCenter

@Composable
fun HomeScreen(
    vm: MainViewModel,
    needsSetup: Boolean,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onLogs: (String) -> Unit
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val avail by vm.availability.collectAsStateWithLifecycle()
    val runs by SyncCenter.states.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("FlowSync") },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Check destinations") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New profile") }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (needsSetup) {
                item {
                    Card(
                        onClick = onSettings,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Background sync is not fully set up", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("Tap to fix notification / battery permissions so syncs keep running.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }
            if (profiles.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Sync, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("No sync profiles yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Create one to sync a phone folder with SMB, FTP, SFTP or WebDAV.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(profiles, key = { it.id }) { p ->
                ProfileCard(
                    p = p,
                    a = avail[p.id] ?: Availability(if (p.enabled) DotStatus.CHECKING else DotStatus.PAUSED),
                    run = runs[p.id],
                    onClick = { onEdit(p.id) },
                    onToggle = { vm.setEnabled(p.id, it) },
                    onRun = { vm.runNow(p.id) },
                    onStop = { vm.stop(p.id) },
                    onDuplicate = { vm.duplicate(p.id) },
                    onLogs = { onLogs(p.id) },
                    onDelete = { vm.delete(p.id) }
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    p: SyncProfile,
    a: Availability,
    run: RunState?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDuplicate: () -> Unit,
    onLogs: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val statusText = when (a.status) {
        DotStatus.ONLINE -> "Destination online"
        DotStatus.OFFLINE -> "Destination offline" + if (a.detail.isNotBlank()) " · ${a.detail}" else ""
        DotStatus.CHECKING -> "Checking destination…"
        DotStatus.PAUSED -> "Paused"
    }
    val statusColor = when (a.status) {
        DotStatus.ONLINE -> OnlineGreen
        DotStatus.OFFLINE -> OfflineRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(a.status)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = p.enabled, onCheckedChange = onToggle)
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onClick() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("View log") }, onClick = { menu = false; onLogs() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("From  ${Format.summary(p.source)}", style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("To      ${Format.summary(p.destination)}", style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(p.mode.label)
                when (p.filter.mode) {
                    FilterMode.ALL -> {}
                    FilterMode.ONLY -> Tag("only " + p.filter.extensions.take(4).joinToString(",") + if (p.filter.extensions.size > 4) "…" else "")
                    FilterMode.EXCLUDE -> Tag("skip " + p.filter.extensions.take(4).joinToString(",") + if (p.filter.extensions.size > 4) "…" else "")
                }
                if (p.schedule.enabled) Tag("every " + intervalLabel(p.schedule.intervalMinutes))
                if (!p.includeSubfolders) Tag("no subfolders")
            }

            if (run != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${run.message}  ·  ${run.copied} copied" + if (run.failed > 0) ", ${run.failed} failed" else "",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (p.lastRunAt > 0)
                        "Last: " + DateUtils.getRelativeTimeSpanString(p.lastRunAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                            "\n" + p.lastResult
                    else "Never synced",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                if (run != null) {
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                    }
                } else {
                    Button(onClick = onRun, enabled = p.enabled) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sync now")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete profile?") },
            text = { Text("\"${p.name}\" will be removed. Files already synced are not touched.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

fun intervalLabel(min: Int): String = when {
    min < 60 -> "${min}m"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min}m"
}
