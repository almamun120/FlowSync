@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.flowsync.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.flowsync.fs.GDriveAuth
import com.google.android.gms.auth.api.identity.Identity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.flowsync.model.EndpointConfig
import app.flowsync.model.FileFilter
import app.flowsync.model.FilterMode
import app.flowsync.model.Protocol
import app.flowsync.model.SyncMode
import app.flowsync.model.SyncProfile
import app.flowsync.sync.Extensions
import app.flowsync.util.Format
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(vm: MainViewModel, id: String?, onClose: () -> Unit) {
    val existing = remember(id) { id?.let { vm.getProfile(it) } }
    var p by remember {
        mutableStateOf(existing ?: SyncProfile(destination = EndpointConfig(protocol = Protocol.SMB)))
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun validate(): String? {
        if (p.name.isBlank()) return "Give the profile a name."
        for ((label, c) in listOf("Source" to p.source, "Destination" to p.destination)) {
            when (c.protocol) {
                Protocol.LOCAL -> if (c.localUri.isBlank()) return "$label: choose a folder."
                Protocol.SMB -> {
                    if (c.host.isBlank()) return "$label: enter the host."
                    if (c.share.isBlank()) return "$label: enter the share name."
                }
                Protocol.GDRIVE -> if (c.username.isBlank()) return "$label: sign in with Google."
                else -> if (c.host.isBlank()) return "$label: enter the host."
            }
        }
        if (p.filter.mode != FilterMode.ALL && p.filter.extensions.isEmpty()) return "Add at least one file extension, or choose “All files”."
        return null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New profile" else "Edit profile") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = {
                        val e = validate()
                        if (e != null) error = e else { vm.save(p); onClose() }
                    }) { Text("Save") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("General") {
                Field("Profile name", p.name, { p = p.copy(name = it) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(p.enabled, { p = p.copy(enabled = it) })
                }
            }

            EndpointCard("Source (from)", p.source, vm, needWrite = p.mode == SyncMode.MOVE) { p = p.copy(source = it) }
            EndpointCard("Destination (to)", p.destination, vm, needWrite = true) { p = p.copy(destination = it) }

            Section("Sync mode") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncMode.values().forEach { m ->
                        FilterChip(selected = p.mode == m, onClick = { p = p.copy(mode = m) }, label = { Text(m.label) })
                    }
                }
                Text(p.mode.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include subfolders", Modifier.weight(1f))
                    Switch(p.includeSubfolders, { p = p.copy(includeSubfolders = it) })
                }
            }

            FilterSection(p.filter) { p = p.copy(filter = it) }

            Section("Schedule & conditions") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Run automatically in background", Modifier.weight(1f))
                    Switch(p.schedule.enabled, { p = p.copy(schedule = p.schedule.copy(enabled = it)) })
                }
                if (p.schedule.enabled) {
                    Text("Repeat every", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 180, 360, 720, 1440).forEach { m ->
                            FilterChip(
                                selected = p.schedule.intervalMinutes == m,
                                onClick = { p = p.copy(schedule = p.schedule.copy(intervalMinutes = m)) },
                                label = { Text(intervalLabel(m)) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Wi-Fi / unmetered network only", Modifier.weight(1f))
                        Switch(p.schedule.wifiOnly, { p = p.copy(schedule = p.schedule.copy(wifiOnly = it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Only while charging", Modifier.weight(1f))
                        Switch(p.schedule.chargingOnly, { p = p.copy(schedule = p.schedule.copy(chargingOnly = it)) })
                    }
                    Text(
                        "Android allows a minimum interval of 15 minutes. “Sync now” always ignores these conditions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val e = validate()
                    if (e != null) error = e else { vm.save(p); onClose() }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Save profile") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilterSection(f: FileFilter, onChange: (FileFilter) -> Unit) {
    var text by remember { mutableStateOf(f.extensions.joinToString(", ")) }
    fun setExts(list: List<String>) {
        text = list.joinToString(", ")
        onChange(f.copy(extensions = list))
    }
    Section("File filter (by extension)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMode.values().forEach { m ->
                FilterChip(selected = f.mode == m, onClick = { onChange(f.copy(mode = m)) }, label = { Text(m.label) })
            }
        }
        if (f.mode != FilterMode.ALL) {
            Text("Quick presets", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Extensions.presets.forEach { (name, exts) ->
                    val selected = exts.all { it in f.extensions }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) f.extensions - exts.toSet() else (f.extensions + exts).distinct()
                            setExts(next)
                        },
                        label = { Text(name) }
                    )
                }
            }
            Field(
                "Custom extensions", text,
                { text = it; onChange(f.copy(extensions = Extensions.parse(it))) },
                placeholder = "jpg, png, mp4, pdf, apk …", singleLine = false
            )
            Text(
                if (f.mode == FilterMode.ONLY) "Only files with these extensions are synced."
                else "Files with these extensions are skipped (and never deleted in Mirror mode).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Skip hidden files (.name)", Modifier.weight(1f))
            Switch(f.skipHidden, { onChange(f.copy(skipHidden = it)) })
        }
        Field(
            "Skip files larger than (MB, 0 = no limit)",
            if (f.maxSizeMb == 0L) "" else f.maxSizeMb.toString(),
            { onChange(f.copy(maxSizeMb = it.filter { c -> c.isDigit() }.toLongOrNull() ?: 0L)) },
            number = true, placeholder = "0"
        )
    }
}

@Composable
private fun EndpointCard(
    title: String,
    cfg: EndpointConfig,
    vm: MainViewModel,
    needWrite: Boolean,
    onChange: (EndpointConfig) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onChange(cfg.copy(localUri = uri.toString()))
            result = null
        }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                .onSuccess { onChange(cfg.copy(privateKey = it)) }
        }
    }

    fun finishSignIn(token: String?) {
        if (token == null) { result = "✗ No access token received"; return }
        scope.launch {
            val email = vm.driveEmail(token)
            if (email == null) result = "✗ Could not read the Google account"
            else { onChange(cfg.copy(username = email)); result = "Signed in ✓" }
        }
    }
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            finishSignIn(Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data).accessToken)
        } catch (e: Exception) { result = "✗ " + (e.message ?: "Sign-in cancelled") }
    }
    fun startSignIn() {
        result = null
        Identity.getAuthorizationClient(ctx).authorize(GDriveAuth.request(cfg.driveFull, null))
            .addOnSuccessListener { res ->
                val pi = res.pendingIntent
                if (res.hasResolution() && pi != null) signInLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else finishSignIn(res.accessToken)
            }
            .addOnFailureListener { result = "✗ " + (it.message ?: "Sign-in failed") }
    }

    Section(title) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Protocol.values().forEach { pr ->
                FilterChip(
                    selected = cfg.protocol == pr,
                    onClick = { onChange(cfg.copy(protocol = pr, port = 0)); result = null },
                    label = { Text(pr.label) }
                )
            }
        }

        when (cfg.protocol) {
            Protocol.LOCAL -> {
                Text(Format.localPath(cfg.localUri), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (cfg.localUri.isBlank()) "Choose folder" else "Change folder")
                }
                Text(
                    "Access is granted once through Android's folder picker and remembered permanently – no root, and it keeps working in the background.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Protocol.GDRIVE -> {
                Text(
                    if (cfg.username.isBlank()) "Not signed in" else "Signed in as ${cfg.username}",
                    style = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(onClick = { startSignIn() }) {
                    Text(if (cfg.username.isBlank()) "Sign in with Google" else "Sign in again")
                }
                Field("Folder in Drive", cfg.path, { onChange(cfg.copy(path = it)) }, placeholder = "FlowSync/Phone")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Full Drive access (see files not created by this app)", Modifier.weight(1f))
                    Switch(cfg.driveFull, { onChange(cfg.copy(driveFull = it, username = "")) })
                }
                Text(
                    "Default access only sees files and folders this app created – ideal for backups. " +
                        "Google Docs/Sheets files are skipped. Deleted files go to the Drive trash.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Text(
                    when (cfg.protocol) {
                        Protocol.SMB -> "NAS: Synology, QNAP, TrueNAS, OpenMediaVault, Windows share, router USB disk. Host = NAS IP, Share = shared folder name."
                        Protocol.FTP -> "NAS: enable FTP service on the NAS (default port 21). Use TLS if the NAS supports it."
                        Protocol.SFTP -> "NAS: enable SSH/SFTP service (Synology default port 22). Use a NAS user that has SSH access."
                        else -> "NAS: Synology WebDAV Server ports 5005 (HTTP) / 5006 (HTTPS); QNAP 80 / 443; Nextcloud: use its /remote.php/dav/files/USER URL."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Field(
                    if (cfg.protocol == Protocol.WEBDAV) "Host or full URL" else "Host / IP", cfg.host,
                    { onChange(cfg.copy(host = it.trim())) },
                    placeholder = if (cfg.protocol == Protocol.WEBDAV) "cloud.example.com  or  https://…/remote.php/dav/files/me" else "192.168.1.10"
                )
                Field(
                    "Port", if (cfg.port == 0) "" else cfg.port.toString(),
                    { onChange(cfg.copy(port = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0)) },
                    number = true,
                    placeholder = if (cfg.protocol == Protocol.WEBDAV) (if (cfg.secure) "443" else "80") else cfg.protocol.defaultPort.toString()
                )
                if (cfg.protocol == Protocol.SMB) {
                    Field("Share name", cfg.share, { onChange(cfg.copy(share = it.trim())) }, placeholder = "Public")
                    Field("Domain / workgroup (optional)", cfg.domain, { onChange(cfg.copy(domain = it.trim())) })
                }
                Field(
                    if (cfg.protocol == Protocol.SMB) "Folder inside share (optional)" else "Folder path",
                    cfg.path, { onChange(cfg.copy(path = it)) }, placeholder = "/Backups/Phone"
                )
                Field("Username (empty = guest / anonymous)", cfg.username, { onChange(cfg.copy(username = it)) })
                Field(
                    if (cfg.protocol == Protocol.SFTP && cfg.privateKey.isNotBlank()) "Password / key passphrase" else "Password",
                    cfg.password, { onChange(cfg.copy(password = it)) }, password = true
                )
                if (cfg.protocol == Protocol.FTP || cfg.protocol == Protocol.WEBDAV) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (cfg.protocol == Protocol.FTP) "Use TLS (FTPS)" else "Use HTTPS", Modifier.weight(1f))
                        Switch(cfg.secure, { onChange(cfg.copy(secure = it)) })
                    }
                }
                if (cfg.protocol == Protocol.SFTP) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (cfg.privateKey.isBlank()) "Import private key" else "Replace private key")
                        }
                        if (cfg.privateKey.isNotBlank()) {
                            TextButton(onClick = { onChange(cfg.copy(privateKey = "")) }) { Text("Remove") }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true; result = null
                    scope.launch {
                        result = vm.testEndpoint(cfg, needWrite)
                        testing = false
                    }
                }
            ) { Text("Test connection") }
            Spacer(Modifier.width(12.dp))
            if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        result?.let {
            Text(
                it,
                color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else app.flowsync.ui.OnlineGreen,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
