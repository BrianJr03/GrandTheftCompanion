package jr.brian.thorhud.net

import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide snapshot of the ThorBridge connection. [hello] holds the latest
 * `hello` frame (ASI's exe fingerprint + supported flag) and sticks across
 * tick updates. [last] holds the most recent frame of any type so the UI
 * can show liveness.
 */
@Singleton
class GameStateRepository @Inject constructor() {

    private val _hello = MutableStateFlow<GameState?>(null)
    val hello: StateFlow<GameState?> = _hello.asStateFlow()

    private val _last = MutableStateFlow(GameState.WAITING)
    val last: StateFlow<GameState> = _last.asStateFlow()

    fun update(frame: GameState) {
        if (frame.isHello) _hello.value = frame
        _last.value = frame
    }
}
