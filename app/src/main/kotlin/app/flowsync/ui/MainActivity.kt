package app.flowsync.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.flowsync.util.AppPermissions
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Editor(val id: String?) : Screen
    data object Settings : Screen
    data class Logs(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FlowSyncTheme { AppRoot(vm) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.monitorLoop() }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var resumeTick by remember { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !AppPermissions.notificationsGranted(ctx)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val needsSetup = remember(resumeTick) {
        !AppPermissions.notificationsGranted(ctx) || !AppPermissions.batteryUnrestricted(ctx)
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            vm = vm, needsSetup = needsSetup,
            onAdd = { screen = Screen.Editor(null) },
            onEdit = { screen = Screen.Editor(it) },
            onSettings = { screen = Screen.Settings },
            onLogs = { screen = Screen.Logs(it) }
        )
        is Screen.Editor -> EditorScreen(vm, s.id) { screen = Screen.Home }
        Screen.Settings -> SettingsScreen(vm, resumeTick) { screen = Screen.Home }
        is Screen.Logs -> LogScreen(vm, s.id) { screen = Screen.Home }
    }
}
