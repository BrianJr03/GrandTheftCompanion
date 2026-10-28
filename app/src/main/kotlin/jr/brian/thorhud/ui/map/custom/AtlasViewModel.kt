package jr.brian.thorhud.ui.map.custom

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jr.brian.thorhud.model.AtlasProgress
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    @ApplicationContext private val appContext: Context,
    private val store: CustomMapStore,
    private val repository: GameStateRepository,
    private val folderPrefs: AtlasFolderPrefs,
    private val viewportPrefs: AtlasViewportPrefs,
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

    // Follow mode: now smooth, so re-enabling cannot clobber the user's
    // last view (fly-to animates from the saved viewport to the player
    // instead of snapping). Session default comes from the saved
    // per-game flag; set at init below.
    private val _followMode = MutableStateFlow(false)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    // One-shot event the atlas screen listens for to trigger a fly-to.
    // Replay 0 so a late subscriber does not fly on open.
    private val _followReEngage = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val followReEngage: SharedFlow<Unit> = _followReEngage.asSharedFlow()

    private val _followSmoothing = MutableStateFlow(FollowSmoothing.fromTauMs(viewportPrefs.readSmoothingMs()))
    val followSmoothing: StateFlow<FollowSmoothing> = _followSmoothing.asStateFlow()

    private val _snapZoomToPresets = MutableStateFlow(viewportPrefs.readSnapZoomToPresets())
    val snapZoomToPresets: StateFlow<Boolean> = _snapZoomToPresets.asStateFlow()

    // Session-local lock on the atlas pan/zoom gesture; same pattern as
    // MapViewModel.isLocked, so a horizontal swipe between tabs can't
    // get swallowed by an accidental drag. Taps (calibration + waypoint)
    // stay live either way.
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun toggleLock() { _isLocked.value = !_isLocked.value }

    // Session-local re-anchor mode (IV-only in UI). While on, the next
    // tap on the atlas pairs the tapped image pixel with Niko's live
    // `pos` and shifts translation only, keeping the existing scale.
    // Lets the user kill a drifted calibration in one tap instead of
    // walking the three-tap flow.
    private val _anchorMode = MutableStateFlow(false)
    val anchorMode: StateFlow<Boolean> = _anchorMode.asStateFlow()

    fun toggleAnchorMode() { _anchorMode.value = !_anchorMode.value }

    /** One-tap re-anchor: keep the game's current calibration scale
     *  (a, b, d, e), shift translation (c, f) so [world] lands at
     *  [pixel]. No-op if the game has no calibration yet (nothing to
     *  nudge). Exits anchor mode on success. */
    fun reanchor(game: CustomMapStore.Game, world: WorldPoint, pixel: PixelPoint) {
        val existing = when (game) {
            CustomMapStore.Game.IV -> _iv.value.calibration
            CustomMapStore.Game.V  -> _v.value.calibration
        } ?: return
        val newC = pixel.px - existing.a * world.x - existing.b * world.y
        val newF = pixel.py - existing.d * world.x - existing.e * world.y
        val nudged = existing.copy(c = newC, f = newF, maxResidualPx = 0f)
        store.writeCalibration(game, nudged)
        when (game) {
            CustomMapStore.Game.IV -> _iv.value = _iv.value.copy(calibration = nudged)
            CustomMapStore.Game.V  -> _v.value  = _v.value.copy(calibration = nudged)
        }
        _anchorMode.value = false
    }

    /** Last saved viewport (zoom + pan) for a game, or null when the
     *  user has never panned/zoomed this game. The screen uses this
     *  on its first fit() call so an app restart lands on the view
     *  the user left. */
    fun savedViewport(game: CustomMapStore.Game): AtlasViewportPrefs.Saved? =
        viewportPrefs.read(game)

    /** Called by the screen on every user-driven pan / zoom; the
     *  SharedPreferences write is cheap enough to do inline. */
    fun saveViewport(game: CustomMapStore.Game,
                     scale: Float, offsetX: Float, offsetY: Float) {
        viewportPrefs.writeViewport(game, scale, offsetX, offsetY)
    }

    val presence: StateFlow<CustomMapStore.AtlasPresence> = store.presence

    /** Latest atlas-extractor progress from the plugin, or null if the
     *  user has never kicked one off this session. The NoImageState
     *  listens to this to swap between the "drop a file" hint, a
     *  running progress line, and a finished / error summary. */
    val atlasProgress: StateFlow<AtlasProgress?> = repository.atlas

    /** The exact adb-reachable path the user needs to drop a map image
     *  at for a given game. The settings panel reads this. */
    fun pathFor(game: CustomMapStore.Game): String = store.atlasPathFor(game)

    /** Latest picked-folder Uri per game, as a stable flow for the
     *  settings panel. Null when nothing is picked (fall back to the
     *  app-private files dir, which only adb can reach on Android 11+). */
    val folderIv: StateFlow<Uri?> = folderPrefs.iv
    val folderV: StateFlow<Uri?> = folderPrefs.v

    /** Persist the user's folder pick for a game. Takes persistable
     *  read permission so the Uri survives process death, then
     *  triggers a refresh so the newly-picked folder's files sync
     *  into app-private storage at once. */
    fun setFolder(game: CustomMapStore.Game, uri: Uri?) {
        if (uri != null) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        } else {
            folderPrefs.uriFor(game)?.let { old ->
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        old,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        folderPrefs.set(game, uri)
        reload(game)
    }

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

    /** Fire the one-shot "follow re-engage" event so the atlas screen
     *  triggers a fly-to. No-op if nothing is listening (saved for an
     *  edge-case debouncer that never materialised). */
    fun requestFollowReEngage() {
        _followReEngage.tryEmit(Unit)
    }

    /** Persist the user's follow default per game and update the
     *  session flag. */
    fun setFollowDefault(game: CustomMapStore.Game, on: Boolean) {
        viewportPrefs.writeFollow(game, on)
        _followMode.value = on
    }

    fun setFollowSmoothing(s: FollowSmoothing) {
        _followSmoothing.value = s
        viewportPrefs.writeSmoothingMs(s.tauMs)
    }

    fun setSnapZoomToPresets(b: Boolean) {
        _snapZoomToPresets.value = b
        viewportPrefs.writeSnapZoomToPresets(b)
    }

    fun savedPresetIndex(game: CustomMapStore.Game): Int =
        viewportPrefs.readPresetIndex(game)

    fun setPresetIndex(game: CustomMapStore.Game, idx: Int) {
        viewportPrefs.writePresetIndex(game, idx)
    }

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

    // -- "Build from my game files" -----------------------------------
    /** Ask the running plugin to extract the atlas out of the game's
     *  own archives. The plugin acks immediately; the real work runs
     *  on a worker thread and reports via [atlasProgress]. */
    fun requestAtlasExtract() {
        viewModelScope.launch { repository.atlasExtract() }
    }
    fun requestAtlasCancel() {
        viewModelScope.launch { repository.atlasCancel() }
    }
}
