package jr.brian.thorhud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: GameStateRepository,
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

    fun setRadio(stationIndex: Int) {
        viewModelScope.launch { repository.setRadio(stationIndex) }
    }

    fun setWeapon(weaponId: Int) {
        viewModelScope.launch { repository.setWeapon(weaponId) }
    }
}
