@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.flowsync.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.flowsync.model.AppConfig
import app.flowsync.util.AppPermissions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, resumeTick: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSecrets by remember { mutableStateOf(true) }
    var pendingImport by remember { mutableStateOf<AppConfig?>(null) }

    val notifOk = remember(resumeTick) { AppPermissions.notificationsGranted(ctx) }
    val batteryOk = remember(resumeTick) { AppPermissions.batteryUnrestricted(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) AppPermissions.openNotificationSettings(ctx)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            Toast.makeText(ctx, vm.exportTo(uri, includeSecrets), Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            vm.readConfig(uri)
                .onSuccess { pendingImport = it }
                .onFailure { Toast.makeText(ctx, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("Background reliability") {
                Text(
                    "These settings stop Android from freezing or killing syncs while the screen is off.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusRow("Notifications (required for background service)", notifOk) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else AppPermissions.openNotificationSettings(ctx)
                }
                StatusRow("Battery: run without restrictions", batteryOk) { AppPermissions.requestIgnoreBattery(ctx) }
                OutlinedButton(onClick = { AppPermissions.openAppSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings (auto-start / background data)")
                }
                Text(
                    "Some phones (Xiaomi, Oppo, Vivo, Huawei, Samsung) also need “Auto-start” enabled and the app locked in Recents. " +
                        "Folder access is granted per profile through the folder picker and stays valid across reboots.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section("Config backup (JSON)") {
                Text(
                    "All profiles are stored as JSON. Export a copy to restore later or move to another phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeSecrets, { includeSecrets = it })
                    Text("Include passwords & private keys")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val name = "flowsync-config-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
                        exportLauncher.launch(name)
                    }, modifier = Modifier.weight(1f)) { Text("Export") }
                    FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.weight(1f)) { Text("Import") }
                }
                Text(
                    "After importing on a new device, re-select local folders in each profile (Android permissions can't be copied).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section("About") {
                Text("FlowSync 1.0 – modern rewrite of the SMBSync2 idea in Kotlin + Jetpack Compose.\nProtocols: Local (SAF), SMB 2/3, FTP/FTPS, SFTP, WebDAV. No root required.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pendingImport?.let { cfg ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import ${cfg.profiles.size} profile(s)") },
            text = { Text("Merge keeps your current profiles and adds/updates the imported ones. Replace removes everything first.") },
            confirmButton = {
                TextButton(onClick = { vm.applyImport(cfg, replace = false); pendingImport = null }) { Text("Merge") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                    TextButton(onClick = { vm.applyImport(cfg, replace = true); pendingImport = null }) { Text("Replace") }
                }
            }
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
            Modifier.size(22.dp), tint = if (ok) OnlineGreen else OfflineRed
        )
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onFix) { Text("Fix") }
    }
}
