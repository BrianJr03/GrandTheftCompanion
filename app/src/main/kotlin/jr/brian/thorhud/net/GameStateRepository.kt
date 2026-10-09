package jr.brian.thorhud.net

import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide snapshot of the latest [GameState] frame. The service writes;
 * composables (and later the Presentation on the bottom screen) read.
 */
@Singleton
class GameStateRepository @Inject constructor() {

    private val _state = MutableStateFlow(GameState.WAITING)
    val state: StateFlow<GameState> = _state.asStateFlow()

    fun update(frame: GameState) {
        _state.value = frame
    }
}
