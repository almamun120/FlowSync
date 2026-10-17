@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.flowsync.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flowsync.model.EndpointConfig
import app.flowsync.rclone.Backend
import app.flowsync.rclone.BackendOption
import app.flowsync.rclone.RcloneDaemon
import app.flowsync.rclone.RcloneManager
import app.flowsync.rclone.RcloneUpdate
import app.flowsync.rclone.RemoteInfo
import app.flowsync.util.AppPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private sealed interface RView {
    data object Home : RView
    data object Pick : RView
    data class Add(val backend: Backend) : RView
    data object Terminal : RView
    data object Servers : RView
    data object Tasks : RView
}

/** Everything rclone: remotes, adding any backend (incl. Google Drive), import/export of rclone.conf, terminal. */
@Composable
fun RcloneScreen(onClose: () -> Unit) {
    var view by remember { mutableStateOf<RView>(RView.Home) }
    BackHandler { if (view != RView.Home) view = RView.Home else onClose() }
    when (val v = view) {
        RView.Home -> RemotesHome(onClose, onAdd = { view = RView.Pick }, onTerminal = { view = RView.Terminal },
            onServers = { view = RView.Servers }, onTasks = { view = RView.Tasks })
        RView.Servers -> ServersView(onBack = { view = RView.Home })
        RView.Tasks -> TasksView(onBack = { view = RView.Home })
        RView.Pick -> BackendPicker(onBack = { view = RView.Home }, onPick = { view = RView.Add(it) })
        is RView.Add -> AddRemote(v.backend, onBack = { view = RView.Pick }, onDone = { view = RView.Home })
        RView.Terminal -> TerminalView(onBack = { view = RView.Home })
    }
}

// ------------------------------------------------------------------ remotes list

@Composable
private fun RemotesHome(onClose: () -> Unit, onAdd: () -> Unit, onTerminal: () -> Unit, onServers: () -> Unit, onTasks: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val remotes by produceState(emptyList<RemoteInfo>(), reload) { value = withContext(Dispatchers.IO) { RcloneManager.listRemotes() } }
    val version by produceState<String?>(null) { value = withContext(Dispatchers.IO) { RcloneManager.version() } }
    val testResults = remember { mutableStateMapOf<String, String>() }
    var deleting by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<RcloneUpdate.Info?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            }
            text.onSuccess { pendingImport = it }.onFailure { Toast.makeText(ctx, "Cannot read file: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val msg = try {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(RcloneManager.exportConf().toByteArray()) }
                "rclone.conf saved"
            } catch (e: Exception) { "Export failed: ${e.message}" }
            withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("rclone remotes") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("Engine") {
                if (!RcloneManager.isAvailable) {
                    Text(
                        "rclone is not included in this build. Build the app with the GitHub workflow (it compiles rclone for Android) – see README.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(version ?: "Starting rclone…", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !checking, onClick = {
                            checking = true; updateInfo = null
                            scope.launch(Dispatchers.IO) {
                                val r = RcloneUpdate.check(version)
                                withContext(Dispatchers.Main) { updateInfo = r; checking = false }
                            }
                        }) { Text("Check for rclone update") }
                        Spacer(Modifier.width(12.dp))
                        if (checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    updateInfo?.let { u ->
                        Text(u.message, style = MaterialTheme.typography.bodyMedium,
                            color = if (u.newer) OfflineRed else MaterialTheme.colorScheme.onSurface)
                        if (u.newer) {
                            Text(
                                "Android does not allow apps to run downloaded programs, so rclone is updated together with the app: " +
                                    "GitHub → Actions → Build APK → Run workflow (rclone_version = latest), then install the new APK. " +
                                    "Your profiles and remotes are kept.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rclone/rclone/releases/tag/${u.latest}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("What’s new in ${u.latest}") }
                        }
                    }
                    Text(
                        "rclone runs inside the app (no root). Any remote you add here can be used as the Source or Destination of a profile, " +
                            "together with local folders, SMB, FTP, SFTP and WebDAV.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section("Remotes") {
                if (remotes.isEmpty()) {
                    Text("No remotes yet. Add Google Drive, Dropbox, OneDrive, S3 … or import an existing rclone.conf.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                remotes.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall)
                                Tag(r.type)
                            }
                            TextButton(onClick = {
                                testResults[r.name] = "Testing…"
                                scope.launch(Dispatchers.IO) {
                                    val res = try {
                                        val o = RcloneDaemon.call("operations/list", JSONObject().put("fs", r.name + ":").put("remote", "")
                                            .put("opt", JSONObject().put("noMimeType", true)))
                                        "Connected ✓ (${o.optJSONArray("list")?.length() ?: 0} items in root)"
                                    } catch (e: Exception) { "✗ " + (e.message ?: "error") }
                                    withContext(Dispatchers.Main) { testResults[r.name] = res }
                                }
                            }) { Text("Test") }
                            IconButton(onClick = { deleting = r.name }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                        testResults[r.name]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else OnlineGreen)
                        }
                    }
                }
                Button(onClick = onAdd, enabled = RcloneManager.isAvailable, modifier = Modifier.fillMaxWidth()) { Text("Add remote") }
            }

            Section("rclone.conf") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import") }
                    FilledTonalButton(
                        onClick = { exportLauncher.launch("rclone.conf") },
                        enabled = remotes.isNotEmpty(), modifier = Modifier.weight(1f)
                    ) { Text("Export") }
                }
                Text(
                    "Already use rclone on a PC? Import your rclone.conf and all your remotes work here. " +
                        "The file contains access tokens/passwords – keep it private.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section("Servers & automation") {
                Button(onClick = onServers, enabled = RcloneManager.isAvailable, modifier = Modifier.fillMaxWidth()) {
                    Text("Servers: HTTP · WebDAV · FTP · SFTP · DLNA · S3 · NFS · Web GUI")
                }
                Text("Turn a remote or phone folder into a server and switch it on/off. Runs in the background.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onTasks, enabled = RcloneManager.isAvailable, modifier = Modifier.fillMaxWidth()) {
                    Text("Saved commands & schedules")
                }
                Text("Save any rclone command (sync, check, dedupe, cleanup…) and run it now or automatically.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Phone files for rclone") {
                val granted = AppPermissions.allFilesGranted()
                Text(
                    if (granted) "All files access: granted ✓ – rclone can use any phone path (profiles: “This phone”, servers, commands)."
                    else "All files access is needed for rclone to read/write normal phone folders directly (e.g. /storage/emulated/0/DCIM).",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!granted) Button(onClick = { AppPermissions.requestAllFiles(ctx) }) { Text("Grant All files access") }
            }

            Section("Advanced") {
                OutlinedButton(onClick = onTerminal, enabled = RcloneManager.isAvailable, modifier = Modifier.fillMaxWidth()) {
                    Text("rclone terminal (run any rclone command)")
                }
                Text(
                    "Full rclone: `config` (interactive wizard), `lsd remote:`, `about remote:`, `check`, `crypt` remotes, `union`, `alias` and every other command or flag.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete remote?") },
            text = { Text("“$name” will be removed from rclone.conf. Cloud files are not touched. Profiles using it will go offline.") },
            confirmButton = { TextButton(onClick = { RcloneManager.deleteRemote(name); deleting = null; reload++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import rclone.conf") },
            text = { Text("Merge adds/updates the remotes from the file and keeps the others. Replace removes all current remotes first.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { RcloneManager.importConf(text, replace = false) }
                        .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                    pendingImport = null; reload++
                }) { Text("Merge") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        runCatching { RcloneManager.importConf(text, replace = true) }
                            .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                        pendingImport = null; reload++
                    }) { Text("Replace") }
                }
            }
        )
    }
}

// ------------------------------------------------------------------ choose backend

private val POPULAR = listOf("drive", "dropbox", "onedrive", "box", "pcloud", "mega", "s3", "b2", "yandex", "webdav", "sftp", "smb", "ftp", "crypt")

@Composable
private fun BackendPicker(onBack: () -> Unit, onPick: (Backend) -> Unit) {
    var query by remember { mutableStateOf("") }
    val result by produceState<Result<List<Backend>>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { RcloneManager.backends() } }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add remote") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val r = result
            when {
                r == null -> { CircularProgressIndicator(Modifier.padding(24.dp)) }
                r.isFailure -> Text("Could not load backends: ${r.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
                else -> {
                    val all = r.getOrThrow()
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Search (e.g. Google Drive, S3, Mega)") }, modifier = Modifier.fillMaxWidth()
                    )
                    if (query.isBlank()) {
                        Text("Popular", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            POPULAR.mapNotNull { n -> all.firstOrNull { it.name == n } }.forEach { b ->
                                FilterChip(selected = false, onClick = { onPick(b) }, label = { Text(prettyName(b)) })
                            }
                        }
                    }
                    val shown = all.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.name }) { b ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 10.dp)) {
                                Text(b.description.ifBlank { b.name }, style = MaterialTheme.typography.bodyLarge)
                                Text(b.name + if (b.isOAuth) "  ·  browser sign-in" else "", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun prettyName(b: Backend) = when (b.name) {
    "drive" -> "Google Drive"; "onedrive" -> "OneDrive"; "dropbox" -> "Dropbox"; "box" -> "Box"; "pcloud" -> "pCloud"
    "s3" -> "S3 / compatible"; "b2" -> "Backblaze B2"; "yandex" -> "Yandex Disk"; "mega" -> "Mega"
    "crypt" -> "Crypt (encrypt)"; else -> b.name.uppercase()
}

// ------------------------------------------------------------------ add / configure one remote

private fun optionVisible(o: BackendOption, values: Map<String, String>): Boolean {
    val p = o.provider
    if (p.isEmpty()) return true
    val sel = values["provider"] ?: ""
    val neg = p.startsWith("!")
    val inList = p.removePrefix("!").split(',').map { it.trim() }.contains(sel)
    return if (neg) !inList else inList
}

private val OAUTH_HIDDEN = setOf("client_id", "client_secret", "token", "auth_url", "token_url", "token_type")

@Composable
private fun AddRemote(backend: Backend, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(backend.name) }
    val values = remember { mutableStateMapOf<String, String>() }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<RcloneManager.AuthJob?>(null) }

    fun openBrowser(url: String) {
        Handler(Looper.getMainLooper()).post {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { Toast.makeText(ctx, "Open this link in a browser: $url", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(backend.description.ifBlank { backend.name }) },
            navigationIcon = { IconButton(onClick = { job?.cancel(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("Remote name", name, { name = it.trim() })

            if (backend.isOAuth) {
                Text(
                    "Sign in through your browser. rclone listens on this phone (127.0.0.1:53682) to receive the login result – " +
                        "no Google Cloud console or developer account needed; rclone's built-in app credentials are used.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Optional: your own OAuth client (faster API quota)", style = MaterialTheme.typography.labelLarge)
                Field("Client ID (optional)", clientId, { clientId = it })
                Field("Client secret (optional)", clientSecret, { clientSecret = it }, password = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy && !ok, onClick = {
                        if (!RcloneManager.validName(name)) { status = "✗ Use letters, digits, _ . + @ - in the name"; return@Button }
                        busy = true; status = "Waiting for browser sign-in…"; authUrl = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val token = RcloneManager.authorize(backend.name, clientId, clientSecret,
                                    onUrl = { u -> authUrl = u; openBrowser(u) }, onJob = { job = it })
                                RcloneManager.saveOAuthRemote(name, backend.name, token, clientId, clientSecret)
                                status = "Remote “$name” created ✓"; ok = true
                            } catch (e: Exception) { status = "✗ " + (e.message ?: "failed") }
                            busy = false
                        }
                    }) { Text("Sign in with browser") }
                    Spacer(Modifier.width(12.dp))
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
                    }
                }
                authUrl?.let { u ->
                    TextButton(onClick = { openBrowser(u) }) { Text("Open sign-in page again") }
                }
                if (backend.name == "onedrive") {
                    Text("OneDrive: if listing fails after sign-in, open the terminal and run “config reconnect ${name}:” to choose the drive.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val shown = backend.options.filter {
                    !it.advanced && it.name !in OAUTH_HIDDEN && optionVisible(it, values)
                }
                if (shown.isEmpty()) Text("This backend has no basic options.", style = MaterialTheme.typography.bodySmall)
                shown.forEach { o -> OptionField(o, values[o.name] ?: "") { values[o.name] = it } }
                Button(enabled = !busy && !ok, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (!RcloneManager.validName(name)) { status = "✗ Use letters, digits, _ . + @ - in the name"; return@Button }
                    val missing = shown.firstOrNull { it.required && (values[it.name] ?: "").isBlank() && it.default.isBlank() }
                    if (missing != null) { status = "✗ “${missing.name}” is required"; return@Button }
                    busy = true; status = "Creating…"
                    val params = shown.associate { it.name to (values[it.name] ?: "") }
                    scope.launch(Dispatchers.IO) {
                        try { status = RcloneManager.createRemote(name, backend.name, params) + " ✓"; ok = true }
                        catch (e: Exception) { status = "✗ " + (e.message ?: "failed") }
                        busy = false
                    }
                }) { Text("Create remote") }
                Text("More options (advanced flags, encryption wrappers…) are available in the rclone terminal: `config`.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            status?.let {
                Text(it, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else OnlineGreen,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (ok) Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun OptionField(o: BackendOption, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Field(o.name + if (o.required) " *" else "", value, onChange, placeholder = o.default, password = o.isPassword)
        if (o.help.isNotBlank()) Text(o.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (o.examples.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                o.examples.take(12).forEach { (v, h) ->
                    FilterChip(selected = value == v, onClick = { onChange(v) }, label = { Text(v.ifEmpty { h.take(18) }) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ terminal

private fun splitArgs(s: String): List<String> {
    val out = ArrayList<String>(); val cur = StringBuilder(); var q = '\u0000'; var has = false
    for (ch in s) {
        when {
            q != '\u0000' -> if (ch == q) q = '\u0000' else cur.append(ch)
            ch == '"' || ch == '\'' -> { q = ch; has = true }
            ch.isWhitespace() -> if (cur.isNotEmpty() || has) { out += cur.toString(); cur.setLength(0); has = false }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty() || has) out += cur.toString()
    return out
}

@Composable
private fun TerminalView(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("rclone terminal – type a command (without the word rclone), e.g.  config  or  lsd mydrive:\n") }
    var cmd by remember { mutableStateOf("") }
    var stdin by remember { mutableStateOf("") }
    var proc by remember { mutableStateOf<Process?>(null) }
    var running by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun append(s: String) { output = (output + s).takeLast(30000) }

    fun start(line: String) {
        val args = splitArgs(line).let { if (it.firstOrNull() == "rclone") it.drop(1) else it }
        if (args.isEmpty()) return
        append("\n$ rclone ${args.joinToString(" ")}\n")
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                val p = RcloneManager.builder(*args.toTypedArray()).redirectErrorStream(true).start()
                proc = p
                val buf = CharArray(512)
                p.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        val chunk = String(buf, 0, n)
                        withContext(Dispatchers.Main) { append(chunk) }
                    }
                }
                val code = p.waitFor()
                withContext(Dispatchers.Main) { append("\n[exit $code]\n") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { append("\n✗ ${e.message}\n") }
            }
            withContext(Dispatchers.Main) { running = false; proc = null }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("rclone terminal") },
            navigationIcon = { IconButton(onClick = { proc?.destroyForcibly(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                Text(output, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
            androidx.compose.runtime.LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("config", "listremotes", "version", "config file", "about --help").forEach { c ->
                    FilterChip(selected = false, enabled = !running, onClick = { cmd = c }, label = { Text(c) })
                }
            }
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = stdin, onValueChange = { stdin = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text("Input for running command (Enter = send)") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        val p = proc; val t = stdin; stdin = ""
                        append("$t\n")
                        scope.launch(Dispatchers.IO) { try { p?.outputStream?.apply { write((t + "\n").toByteArray()); flush() } } catch (_: Exception) {} }
                    }) { Text("Send") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { proc?.destroyForcibly() }) { Text("Stop") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cmd, onValueChange = { cmd = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text("rclone …") }
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { start(cmd) }) { Text("Run") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ------------------------------------------------------------------ editor integration

/** Fields shown in the profile editor when protocol = rclone. */
@Composable
fun RcloneEndpointFields(cfg: EndpointConfig, onChange: (EndpointConfig) -> Unit, onManage: () -> Unit) {
    val remotes by produceState(emptyList<RemoteInfo>()) { value = withContext(Dispatchers.IO) { RcloneManager.listRemotes() } }
    if (!RcloneManager.isAvailable) {
        Text("rclone is not included in this build – use the GitHub workflow build (see README).",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (remotes.isEmpty()) {
        Text("No rclone remotes yet.", style = MaterialTheme.typography.bodyMedium)
    }
    run {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cfg.host == ":local", onClick = { onChange(cfg.copy(host = ":local")) },
                label = { Text("This phone (all files)") })
            remotes.forEach { r ->
                FilterChip(selected = cfg.host == r.name, onClick = { onChange(cfg.copy(host = r.name)) },
                    label = { Text("${r.name}  ·  ${r.type}") })
            }
        }
    }
    FilledTonalButton(onClick = onManage) { Text("Manage remotes (add Google Drive, Dropbox …)") }
    Field("Folder in remote", cfg.path, { onChange(cfg.copy(path = it)) }, placeholder = "Backups/Phone")
}
