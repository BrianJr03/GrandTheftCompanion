package jr.brian.thorhud.ui.map

import android.content.Intent
import android.util.Size
import android.view.Surface
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import jr.brian.thorhud.mirror.ScreenMirror
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.prefs.HudPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val repository: GameStateRepository,
) : ViewModel() {
    val status: StateFlow<ScreenMirror.Status> = mirror.status
    val captureSize: StateFlow<Size?> = mirror.captureSize

    // ---- map blips / route ----
    /** The plugin's blip census, nearest first; empty until it sends one. */
    val blips: StateFlow<List<GameState.Blip>> = repository.blips
    /** Latest state frame, for which blip is routed (see
     *  [GameState.routedBlipId]). */
    val state: StateFlow<GameState?> = repository.state

    // The companion-level selection: whichever blip the user last
    // chose from the panel. Drives the map's "driving to <name>"
    // overlay immediately on tap, without waiting for the plugin to
    // echo a route_blip ack back through the state frame.
    private val _selectedRouteId = MutableStateFlow<Int?>(null)
    val selectedRouteId: StateFlow<Int?> = _selectedRouteId.asStateFlow()

    // Fire-and-forget like setRadio: the state frame is the feedback
    // (the routed blip lights up), and the plugin logs any refusal.
    fun routeBlip(id: Int) {
        _selectedRouteId.value = id
        viewModelScope.launch { repository.routeBlip(id) }
    }
    fun routeClear() {
        _selectedRouteId.value = null
        viewModelScope.launch { repository.routeClear() }
    }
    fun blipCensus() {
        viewModelScope.launch { repository.blipCensus() }
    }
    fun armoryProbe() {
        viewModelScope.launch { repository.armoryProbe() }
    }

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
