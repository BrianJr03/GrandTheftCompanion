package jr.brian.thorhud

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * Keeps the screen on while visible so the HUD doesn't dim mid-session.
 */
@AndroidEntryPoint
class HudActivity : ComponentActivity() {

    @Inject lateinit var client: GameStateClient
    @Inject lateinit var repository: GameStateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            client.frames().collect { repository.update(it) }
        }

        setContent {
            GtcTheme {
                MainScreen()
            }
        }
    }
}
