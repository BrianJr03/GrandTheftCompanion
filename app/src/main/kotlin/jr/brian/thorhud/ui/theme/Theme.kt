package jr.brian.thorhud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark + true-OLED-friendly palette. The HUD always renders over the
// AMOLED bottom panel, so we hard-pin pure-black backgrounds and the
// GTA-IV-green accent regardless of system wallpaper or system dark-
// mode state.
private val HudColors = darkColorScheme(
    primary       = Color(0xFF78DC9F),
    onPrimary     = Color(0xFF003919),
    secondary     = Color(0xFFB6CCBC),
    background    = Color.Black,
    onBackground  = Color(0xFFEDEDED),
    surface       = Color.Black,
    onSurface     = Color(0xFFEDEDED),
)

@Composable
fun ThorHudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HudColors, content = content)
}
