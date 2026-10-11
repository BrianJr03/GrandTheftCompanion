package jr.brian.thorhud.net

import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide snapshot of the ThorBridge connection.
 *
 *  [hello] — latest `hello` frame from the ASI (exe fingerprint).
 *  [state] — latest `state` frame (real game data). Sticks across tick-only
 *            frames so the HUD doesn't flicker during pauses.
 *  [last]  — most recent frame of any type (used for liveness).
 *  [ack]   — latest `ack` frame received for a client->server command.
 *  [stations] / [weapons] — GTA V only: latest radio-station list and
 *            owned-weapon list. Empty for GTA IV.
 *  [lastUpdateMs] — wall clock of the last frame; the UI compares against
 *                   System.currentTimeMillis() to decide "stale."
 */
@Singleton
class GameStateRepository @Inject constructor(
    private val client: GameStateClient,
) {

    private val _hello = MutableStateFlow<GameState?>(null)
    val hello: StateFlow<GameState?> = _hello.asStateFlow()

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> = _state.asStateFlow()

    private val _last = MutableStateFlow(GameState.WAITING)
    val last: StateFlow<GameState> = _last.asStateFlow()

    private val _ack = MutableStateFlow<GameState?>(null)
    val ack: StateFlow<GameState?> = _ack.asStateFlow()

    private val _stations = MutableStateFlow<List<GameState.Station>>(emptyList())
    val stations: StateFlow<List<GameState.Station>> = _stations.asStateFlow()

    private val _weapons = MutableStateFlow<List<GameState.VWeapon>>(emptyList())
    val weapons: StateFlow<List<GameState.VWeapon>> = _weapons.asStateFlow()

    private val _lastUpdateMs = MutableStateFlow(0L)
    val lastUpdateMs: StateFlow<Long> = _lastUpdateMs.asStateFlow()

    /** Latest connection-level error for the setup screen. */
    val lastError: StateFlow<String?> = client.lastError

    fun update(frame: GameState) {
        if (frame.isHello) {
            // A hello opens every connection. If the game on the other
            // end changed (quit GTA IV, launched GTA V or vice versa),
            // nothing cached from the previous one applies any more.
            if (_hello.value?.game != frame.game) {
                _state.value = null
                _stations.value = emptyList()
                _weapons.value = emptyList()
            }
            _hello.value = frame
        }
        if (frame.isState) _state.value = frame
        if (frame.isAck) _ack.value = frame
        if (frame.isStations) _stations.value = frame.stations
        if (frame.isWeapons) _weapons.value = frame.weapons
        _last.value = frame
        _lastUpdateMs.value = System.currentTimeMillis()
    }

    suspend fun setWeapon(weaponId: Int): Int? = client.send("set_weapon", weaponId)
    suspend fun setRadio(stationIndex: Int): Int? = client.send("set_radio", stationIndex)
}
