package dev.injun.remotesync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Seed = Color(0xFF3D5AFE)

private val LightColors = lightColorScheme(
    primary = Seed,
    secondary = Color(0xFF00897B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    secondary = Color(0xFF4DB6AC),
)

@Composable
fun RemoteSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
