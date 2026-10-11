package app.flowsync.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F0E8), onPrimaryContainer = Color(0xFF00201D),
    secondaryContainer = Color(0xFFD7E8E5), tertiary = Color(0xFF4F5FA8),
    background = Color(0xFFF6FAF9), surface = Color(0xFFF6FAF9)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8C8), onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFB2F0E8),
    secondaryContainer = Color(0xFF34413F), tertiary = Color(0xFFB9C3FF),
    background = Color(0xFF0F1413), surface = Color(0xFF0F1413)
)

val OnlineGreen = Color(0xFF22C55E)
val OfflineRed = Color(0xFFEF4444)
val IdleGray = Color(0xFF9AA5A3)

@Composable
fun FlowSyncTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
