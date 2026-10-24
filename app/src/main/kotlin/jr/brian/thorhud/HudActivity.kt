package jr.brian.thorhud

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import jr.brian.thorhud.led.PaintLightsController
import jr.brian.thorhud.led.PoliceLightsController
import jr.brian.thorhud.mirror.ScreenMirror
import jr.brian.thorhud.net.GameStateClient
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.ui.MainScreen
import jr.brian.thorhud.ui.MainViewModel
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
    @Inject lateinit var policeLights: PoliceLightsController
    @Inject lateinit var paintLights: PaintLightsController
    @Inject lateinit var mirror: ScreenMirror

    // Same instance Compose gets via hiltViewModel(); exposes
    // toggleBlips() so dpad-down can open/close the blips panel
    // without the companion needing input focus.
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        lifecycleScope.launch {
            client.frames().collect { repository.update(it) }
        }

        // Thumbstick lights follow the wanted level, but only while the
        // HUD is on screen. Leaving STARTED cancels the controller,
        // which hands the sticks back on its way out.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { policeLights.run() }
        }
        // Second lights controller: mirrors the car's last custom
        // paint to the sticks when the toggle is on. Internal priority
        // yields to policeLights while the player is wanted so the
        // chase look still wins.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { paintLights.run() }
        }

        setContent {
            GtcTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        // Keep capture across config changes; drop it when the Activity
        // is really going away.
        if (isFinishing) mirror.stop()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Dpad-down on the Thor's physical gamepad toggles the blips
        // panel. One edge per press (ignore repeats and the up event)
        // so holding dpad-down does not thrash open/close.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            mainViewModel.toggleBlips()
            return true
        }
        return super.dispatchKeyEvent(event)
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
