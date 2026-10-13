package jr.brian.thorhud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.BuildConfig
import jr.brian.thorhud.GtcSettings
import jr.brian.thorhud.led.StickLights
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.prefs.HudPrefs
import jr.brian.thorhud.update.UpdateChecker
import jr.brian.thorhud.update.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * A weapon tapped on the GTA V weapon wheel.
 *
 * With [error] null the request is on its way or was accepted; the wheel
 * shows [hash] as selected even before the game reports it equipped
 * (while the wheel button is held, the plugin only equips on release).
 * [error] is the reason the request failed, in the plugin's words
 * ("not on foot", "weapon not owned") or the app's ("not connected").
 */
data class WeaponPick(val hash: Long, val error: String? = null)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: GameStateRepository,
    private val settings: GtcSettings,
    private val prefs: HudPrefs,
    stickLights: StickLights,
) : ViewModel() {
    val hello: StateFlow<GameState?> = repository.hello
    val state: StateFlow<GameState?> = repository.state
    val last: StateFlow<GameState> = repository.last
    val ack: StateFlow<GameState?> = repository.ack
    val lastUpdateMs: StateFlow<Long> = repository.lastUpdateMs
    val lastError: StateFlow<String?> = repository.lastError

    // GTA V only (empty for GTA IV).
    val stations: StateFlow<List<GameState.Station>> = repository.stations
    val weapons: StateFlow<List<GameState.VWeapon>> = repository.weapons

    // One event per dpad-down press the plugin reports. SharedFlow so
    // each press reaches a collector in order — the equivalent read via
    // state.dpadDown misses presses whose single-frame true value gets
    // conflated by the StateFlow.
    val dpadDownPressed = repository.dpadDownPressed

    // Red/blue thumbstick lights while wanted: the user's switch, and
    // whether the commands have anywhere to go.
    val policeLightsEnabled: StateFlow<Boolean> = settings.policeLightsEnabled
    val stickLightsStatus: StateFlow<StickLights.Status> = stickLights.status

    fun togglePoliceLights() {
        settings.setPoliceLightsEnabled(!settings.policeLightsEnabled.value)
    }

    /** Pager page to open on (per game). Persisted. */
    fun pagerPage(gameKey: String): Int = prefs.pagerPage(gameKey)
    fun setPagerPage(gameKey: String, page: Int) = prefs.setPagerPage(gameKey, page)

    var hasSeenSwipeHint: Boolean
        get() = prefs.hasSeenSwipeHint
        set(value) { prefs.hasSeenSwipeHint = value }

    // Settings sheet toggles. Backed by HudPrefs StateFlows so Compose
    // sees every flip and the choice survives process death.
    val autoSwipeOnDrive: StateFlow<Boolean> = prefs.autoSwipeOnDrive
    val autoStartMirror: StateFlow<Boolean> = prefs.autoStartMirror
    val alwaysReturnToHud: StateFlow<Boolean> = prefs.alwaysReturnToHud
    val mapControlsRight: StateFlow<Boolean> = prefs.mapControlsRight
    fun setAutoSwipeOnDrive(enabled: Boolean) = prefs.setAutoSwipeOnDrive(enabled)
    fun setAutoStartMirror(enabled: Boolean) = prefs.setAutoStartMirror(enabled)
    fun setAlwaysReturnToHud(enabled: Boolean) = prefs.setAlwaysReturnToHud(enabled)
    fun setMapControlsRight(enabled: Boolean) = prefs.setMapControlsRight(enabled)

    // Blips-panel visibility, hoisted to the ViewModel so a hardware
    // key (dpad-down on the Thor) can toggle it from HudActivity's
    // dispatchKeyEvent before Compose even sees the event.
    private val _showBlips = MutableStateFlow(false)
    val showBlips: StateFlow<Boolean> = _showBlips.asStateFlow()
    fun toggleBlips() { _showBlips.value = !_showBlips.value }
    fun setBlips(open: Boolean) { _showBlips.value = open }

    fun setRadio(stationIndex: Int) {
        viewModelScope.launch { repository.setRadio(stationIndex) }
    }

    // ---- GTA V weapon wheel ----
    private val _weaponPick = MutableStateFlow<WeaponPick?>(null)
    val weaponPick: StateFlow<WeaponPick?> = _weaponPick.asStateFlow()
    private var pickJob: Job? = null

    /**
     * Ask the game to equip [hash] and track the answer in [weaponPick].
     * A newer pick replaces one that is still waiting for its ack.
     */
    fun pickWeapon(hash: Long) {
        pickJob?.cancel()
        _weaponPick.value = WeaponPick(hash)
        pickJob = viewModelScope.launch {
            val id = repository.setWeapon(hash)
            val ack = if (id == null) null else withTimeoutOrNull(ACK_TIMEOUT_MS) {
                repository.ack.first { it?.ackId == id }
            }
            val error = when {
                id == null     -> "not connected"
                ack == null    -> "no reply from the game"
                ack.ok != true -> ack.err?.takeIf { it.isNotBlank() } ?: "refused"
                else           -> null
            }
            if (error != null) _weaponPick.value = WeaponPick(hash, error)
        }
    }

    /** Forget the pick: the wheel closed, or the game confirmed it. */
    fun clearWeaponPick() {
        pickJob?.cancel()
        _weaponPick.value = null
    }

    // ---- In-app updates ----
    private val _updateInfo = MutableStateFlow(UpdateInfo.EMPTY)
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    /** Current running version (release) with any -debug/-rc suffix
     *  intact; the comparator strips suffixes itself. */
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /** Fire-and-forget check against the GitHub Releases API. Called
     *  once from HudActivity on first start; safe to call again — it
     *  just overwrites the flow with whatever the API returns. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _updateInfo.value = UpdateChecker.checkForUpdate(currentVersionName)
        }
    }

    /** Latest release the user has already dismissed the auto-popup
     *  for. MainScreen reads this once at start to decide whether to
     *  pop the dialog automatically; the status-bar chip ignores it. */
    val dismissedUpdateVersion: String get() = prefs.dismissedUpdateVersion

    /** Remember that the user dismissed this version so we do not
     *  auto-open the dialog again until a newer tag lands. */
    fun markUpdateDismissed(version: String) {
        if (version.isNotBlank()) prefs.dismissedUpdateVersion = version
    }

    private companion object {
        // The plugin answers on its next script tick (25 Hz) and refuses
        // commands that sat for 2 s itself, so anything slower than this
        // is not coming.
        const val ACK_TIMEOUT_MS = 2500L
    }
}
