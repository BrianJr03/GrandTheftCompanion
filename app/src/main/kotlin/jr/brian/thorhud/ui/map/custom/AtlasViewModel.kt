package jr.brian.thorhud.ui.map.custom

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the custom-map (atlas) page.
 *
 * Owns:
 *  - the loaded overview bitmap for each game (decoded off the main
 *    thread, swapped out when the connected game changes)
 *  - the latest calibration for each game (read from disk at load,
 *    updated when the user accepts a new one)
 *  - the three-tap calibration flow state
 *  - the "follow the player" toggle
 *
 * The screen reads the state flows and tells the VM when the user
 * accepts a calibration, taps during calibration, pans the viewport,
 * or long-presses to drop a waypoint.
 */
@HiltViewModel
class AtlasViewModel @Inject constructor(
    private val store: CustomMapStore,
    private val repository: GameStateRepository,
) : ViewModel() {

    data class AtlasData(
        val overview: Bitmap? = null,
        val calibration: Calibration? = null,
    )

    data class CalibrationFlow(
        val active: Boolean = false,
        val worlds: List<WorldPoint> = emptyList(),
        val pixels: List<PixelPoint> = emptyList(),
        /** When set, the next tap pairs with this world point (from
         *  "use my position" rather than a blip). */
        val pendingWorld: WorldPoint? = null,
        /** When set, the solve finished — UI shows the residual and
         *  waits for accept / redo. */
        val proposedResult: CalibrationResult? = null,
    )

    private val _iv = MutableStateFlow(AtlasData())
    val iv: StateFlow<AtlasData> = _iv.asStateFlow()
    private val _v = MutableStateFlow(AtlasData())
    val v: StateFlow<AtlasData> = _v.asStateFlow()

    private val _flow = MutableStateFlow(CalibrationFlow())
    val calibrationFlow: StateFlow<CalibrationFlow> = _flow.asStateFlow()

    private val _followMode = MutableStateFlow(true)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    val presence: StateFlow<CustomMapStore.AtlasPresence> = store.presence

    /** The exact adb-reachable path the user needs to drop a map image
     *  at for a given game. The settings panel reads this. */
    fun pathFor(game: CustomMapStore.Game): String = store.atlasPathFor(game)

    init {
        reload(CustomMapStore.Game.IV)
        reload(CustomMapStore.Game.V)
    }

    /** Re-read the image + calibration from disk; call after the user
     *  pushes a new file in and the app resumes. */
    fun reload(game: CustomMapStore.Game) {
        viewModelScope.launch {
            store.refresh()
            val cal = store.readCalibration(game)
            val overview = withContext(Dispatchers.IO) { store.decodeOverview(game) }
            when (game) {
                CustomMapStore.Game.IV ->
                    _iv.value = AtlasData(overview = overview, calibration = cal)
                CustomMapStore.Game.V  ->
                    _v.value  = AtlasData(overview = overview, calibration = cal)
            }
        }
    }

    fun dataFor(game: CustomMapStore.Game): StateFlow<AtlasData> = when (game) {
        CustomMapStore.Game.IV -> iv
        CustomMapStore.Game.V  -> v
    }

    // -- Calibration flow ---------------------------------------------
    fun beginCalibration() {
        _flow.value = CalibrationFlow(active = true)
    }
    fun cancelCalibration() {
        _flow.value = CalibrationFlow()
    }

    /** Record a (world, pixel) pair during the flow. Caller supplies
     *  the world point (either picked from a blip or captured via
     *  "use my position"). */
    fun addCalibrationPair(world: WorldPoint, pixel: PixelPoint) {
        val cur = _flow.value
        if (!cur.active) return
        val worlds = cur.worlds + world
        val pixels = cur.pixels + pixel
        val proposed = if (worlds.size >= CalibrationModel.MIN_PAIRS) {
            CalibrationModel.solve(worlds.zip(pixels).map { (w, p) -> CalibrationPair(w, p) })
        } else null
        _flow.value = cur.copy(
            worlds = worlds, pixels = pixels,
            pendingWorld = null, proposedResult = proposed,
        )
    }

    /** Primed by "use my position": the next tap pairs with this
     *  world coord. */
    fun armUseMyPosition(world: WorldPoint) {
        _flow.value = _flow.value.copy(pendingWorld = world)
    }

    fun acceptCalibration(game: CustomMapStore.Game) {
        val proposed = _flow.value.proposedResult
        if (proposed is CalibrationResult.Ok) {
            store.writeCalibration(game, proposed.calibration)
            when (game) {
                CustomMapStore.Game.IV ->
                    _iv.value = _iv.value.copy(calibration = proposed.calibration)
                CustomMapStore.Game.V  ->
                    _v.value  = _v.value.copy(calibration = proposed.calibration)
            }
        }
        _flow.value = CalibrationFlow()
    }

    fun redoCalibration() {
        _flow.value = CalibrationFlow(active = true)
    }

    // -- Follow mode --------------------------------------------------
    fun setFollowMode(on: Boolean) { _followMode.value = on }

    // -- V-only: long-press to waypoint ------------------------------
    fun requestWaypointXy(x: Float, y: Float) {
        viewModelScope.launch { repository.waypointXy(x, y) }
    }
    fun requestRouteBlip(blipId: Int) {
        viewModelScope.launch { repository.routeBlip(blipId) }
    }
    fun requestRouteClear() {
        viewModelScope.launch { repository.routeClear() }
    }
}
