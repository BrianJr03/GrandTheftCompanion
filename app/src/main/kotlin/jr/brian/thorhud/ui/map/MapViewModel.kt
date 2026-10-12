package jr.brian.thorhud.ui.map

import android.content.Intent
import android.util.Size
import android.view.Surface
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.mirror.ScreenMirror
import jr.brian.thorhud.prefs.HudPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** User's pinch/drag transform over the preset mirror crop, per game. */
data class MirrorTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val mirror: ScreenMirror,
    private val prefs: HudPrefs,
) : ViewModel() {
    val status: StateFlow<ScreenMirror.Status> = mirror.status
    val captureSize: StateFlow<Size?> = mirror.captureSize

    private val _transforms = MutableStateFlow(
        mapOf(
            MirrorGame.IV to loadTransform(MirrorGame.IV),
            MirrorGame.V to loadTransform(MirrorGame.V),
        ),
    )
    val transforms: StateFlow<Map<MirrorGame, MirrorTransform>> = _transforms.asStateFlow()

    // Session-local; starts locked so a swipe between pager pages
    // doesn't nudge the saved layout.
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun toggleLock() {
        _isLocked.value = !_isLocked.value
    }

    fun transformFor(game: MirrorGame): MirrorTransform =
        _transforms.value[game] ?: MirrorTransform()

    fun consentIntent(): Intent = mirror.createConsentIntent()
    fun start(resultCode: Int, data: Intent) = mirror.start(resultCode, data)
    fun stop() = mirror.stop()

    fun attach(surface: Surface) = mirror.attach(surface)
    fun detach(surface: Surface) = mirror.detach(surface)

    fun updateTransform(game: MirrorGame, t: MirrorTransform) {
        _transforms.value = _transforms.value + (game to t)
        prefs.setMirrorTransform(game.key, t.scale, t.offsetX, t.offsetY)
    }

    fun resetTransform(game: MirrorGame) {
        _transforms.value = _transforms.value + (game to MirrorTransform())
        prefs.resetMirrorTransform(game.key)
    }

    private fun loadTransform(game: MirrorGame): MirrorTransform = MirrorTransform(
        scale = prefs.mirrorScale(game.key),
        offsetX = prefs.mirrorOffsetX(game.key),
        offsetY = prefs.mirrorOffsetY(game.key),
    )

    private val MirrorGame.key get() = when (this) {
        MirrorGame.IV -> "iv"
        MirrorGame.V -> "v"
    }
}
