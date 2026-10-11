@file:OptIn(ExperimentalMaterial3Api::class)

package app.flowsync.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogScreen(vm: MainViewModel, id: String, onClose: () -> Unit) {
    val name = remember(id) { vm.getProfile(id)?.name ?: "Log" }
    var lines by remember { mutableStateOf(vm.readLog(id)) }
    val state = rememberLazyListState()
    LaunchedEffect(lines) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Log · $name") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { lines = vm.readLog(id) }) { Icon(Icons.Default.Refresh, "Reload") }
                IconButton(onClick = { vm.clearLog(id); lines = emptyList() }) { Icon(Icons.Default.Delete, "Clear") }
            }
        )
    }) { pad ->
        if (lines.isEmpty()) {
            Text("No log yet.", Modifier.padding(pad).padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), state = state) {
                items(lines) { l ->
                    Text(
                        l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        color = if (l.contains("FAILED") || l.contains("Failed")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
