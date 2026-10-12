package jr.brian.thorhud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.GtcSettings
import jr.brian.thorhud.led.StickLights
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: GameStateRepository,
    private val settings: GtcSettings,
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

    fun setRadio(stationIndex: Int) {
        viewModelScope.launch { repository.setRadio(stationIndex) }
    }

    fun setWeapon(weaponId: Int) {
        viewModelScope.launch { repository.setWeapon(weaponId) }
    }
}
