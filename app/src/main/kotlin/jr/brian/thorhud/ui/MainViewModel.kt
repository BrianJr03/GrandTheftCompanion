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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    fun setRadio(stationIndex: Int) {
        viewModelScope.launch { repository.setRadio(stationIndex) }
    }

    fun setWeapon(weaponId: Int) {
        viewModelScope.launch { repository.setWeapon(weaponId) }
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
}
