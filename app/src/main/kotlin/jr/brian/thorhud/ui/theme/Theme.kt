package jr.brian.thorhud.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF156A3B),
    onPrimary = Color.White,
    secondary = Color(0xFF4F6356),
    background = Color(0xFFFBFDF8),
    surface = Color(0xFFFBFDF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF78DC9F),
    onPrimary = Color(0xFF003919),
    secondary = Color(0xFFB6CCBC),
    background = Color(0xFF171D18),
    surface = Color(0xFF171D18),
)

@Composable
fun ThorHudTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
