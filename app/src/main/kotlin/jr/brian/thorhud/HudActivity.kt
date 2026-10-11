package jr.brian.thorhud

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import jr.brian.thorhud.net.GameStateClient
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.ui.MainScreen
import jr.brian.thorhud.ui.theme.GtcTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The HUD Activity. Always launched on the Thor's bottom display by
 * [MainActivity]'s trampoline. Owns the TCP connection for its lifetime
 * (no foreground service needed — a visible Activity on a secondary
 * display stays alive while the main-screen game runs on display 0).
 *
 * Immersive-sticky: keeps the screen on and hides the system status +
 * navigation bars so the HUD gets the whole 1240×1080 panel. Bars
 * reappear briefly on a swipe from an edge, then auto-hide.
 */
@AndroidEntryPoint
class HudActivity : ComponentActivity() {

    @Inject lateinit var client: GameStateClient
    @Inject lateinit var repository: GameStateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        lifecycleScope.launch {
            client.frames().collect { repository.update(it) }
        }

        setContent {
            GtcTheme {
                MainScreen()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System bars can creep back on focus changes (dialogs, display
        // routing); re-hide every time we regain focus.
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
