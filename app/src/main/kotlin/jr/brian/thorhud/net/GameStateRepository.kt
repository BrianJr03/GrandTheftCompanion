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
 *  [blips] — both games: the map's blips, nearest first, from the
 *            plugin's last census. Empty until the plugin sends one.
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

    private val _blips = MutableStateFlow<List<GameState.Blip>>(emptyList())
    val blips: StateFlow<List<GameState.Blip>> = _blips.asStateFlow()

    private val _lastUpdateMs = MutableStateFlow(0L)
    val lastUpdateMs: StateFlow<Long> = _lastUpdateMs.asStateFlow()

    /** Latest connection-level error for the setup screen. */
    val lastError: StateFlow<String?> = client.lastError

    fun update(frame: GameState) {
        // A hello opens every connection. If the game on the other end
        // changed (quit GTA IV, launched GTA V or vice versa), nothing
        // cached from the previous one applies. Wipe every flow AND
        // hold the UI in the disconnected/setup state until a real
        // state frame from the new game arrives — otherwise the hello
        // itself re-populates _last / _lastUpdateMs and the HUD briefly
        // renders the new game's layout with empty/leftover fields
        // (e.g. GTA IV's "coin" title with no data under it).
        if (frame.isHello && _hello.value?.game != frame.game) {
            _state.value = null
            _stations.value = emptyList()
            _weapons.value = emptyList()
            _blips.value = emptyList()
            _ack.value = null
            _last.value = GameState.WAITING
            _lastUpdateMs.value = 0L
            _hello.value = frame
            return
        }
        if (frame.isHello) _hello.value = frame
        if (frame.isState) _state.value = frame
        if (frame.isAck) _ack.value = frame
        if (frame.isStations) _stations.value = frame.stations
        if (frame.isWeapons) _weapons.value = frame.weapons
        if (frame.isBlips) _blips.value = frame.blips
        _last.value = frame
        _lastUpdateMs.value = System.currentTimeMillis()
    }

    /** [weapon] is a GTA IV weapon id or a GTA V weapon hash; a hash
     *  needs the full unsigned 32-bit range, hence the Long. */
    suspend fun setWeapon(weapon: Long): Int? = client.send("set_weapon", weapon)
    suspend fun setRadio(stationIndex: Int): Int? =
        client.send("set_radio", stationIndex.toLong())

    /** Point the game's route at a blip from [blips]: GTA V sets the
     *  map waypoint on it, GTA IV turns its GPS route on. */
    suspend fun routeBlip(blipId: Int): Int? = client.send("route_blip", blipId.toLong())
    /** Undo [routeBlip]: remove the waypoint / turn the route off. */
    suspend fun routeClear(): Int? = client.send("route_clear", 0L)
    /** Ask the plugin to write its blip census to its log file. */
    suspend fun blipCensus(): Int? = client.send("blip_census", 0L)

    /** Run a cheat by id (see [jr.brian.thorhud.ui.cheats.CheatCatalog]).
     *  One-shots fire once; toggles flip and the next state frame's
     *  `cheats_mask` reports the new state. */
    suspend fun runCheat(cheatId: Int): Int? = client.send("cheat", cheatId.toLong())

    /** Force a reconnect to the plugin; see [GameStateClient.reconnect]. */
    fun reconnect() = client.reconnect()
}
