package jr.brian.thorhud.net

import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide snapshot of the ThorBridge connection. The three flows are
 * sticky in different ways:
 *   - [hello]  holds the latest `hello` frame (ASI's exe fingerprint).
 *   - [state]  holds the latest `state` frame (real game data). Stays at
 *              its last value until a new `state` arrives — the UI uses
 *              [lastUpdateMs] vs. the clock to decide "stale."
 *   - [last]   holds the most recent frame of any type for liveness.
 */
@Singleton
class GameStateRepository @Inject constructor() {

    private val _hello = MutableStateFlow<GameState?>(null)
    val hello: StateFlow<GameState?> = _hello.asStateFlow()

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> = _state.asStateFlow()

    private val _last = MutableStateFlow(GameState.WAITING)
    val last: StateFlow<GameState> = _last.asStateFlow()

    private val _lastUpdateMs = MutableStateFlow(0L)
    val lastUpdateMs: StateFlow<Long> = _lastUpdateMs.asStateFlow()

    fun update(frame: GameState) {
        if (frame.isHello) _hello.value = frame
        if (frame.isState) _state.value = frame
        _last.value = frame
        _lastUpdateMs.value = System.currentTimeMillis()
    }
}
