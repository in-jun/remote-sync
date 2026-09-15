package dev.injun.remotesync.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app keeps its own colours rather than following the wallpaper.
 *
 * The icon is a specific blue, and the app opens in it. Two colours here carry a
 * meaning that has to hold on every phone: the amber of the conflict banner, which
 * asks for a decision, and the red of a sync that failed. A scheme taken from the
 * wallpaper hands those roles whatever it has, and on some phones the two are the
 * same colour.
 */
private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary = BlueGrey30,
    onSecondary = Color.White,
    secondaryContainer = BlueGrey90,
    onSecondaryContainer = Blue10,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber30,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = BlueGrey90,
    onSurfaceVariant = BlueGrey30,
    outline = BlueGrey60,
    outlineVariant = BlueGrey80,
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue10,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    secondary = BlueGrey80,
    onSecondary = Blue10,
    secondaryContainer = BlueGrey30,
    onSecondaryContainer = BlueGrey90,
    tertiary = Amber80,
    onTertiary = Amber30,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = BlueGrey30,
    onSurfaceVariant = BlueGrey80,
    outline = BlueGrey60,
    outlineVariant = BlueGrey30,
)

@Composable
fun RemoteSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
