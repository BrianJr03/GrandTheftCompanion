package jr.brian.thorhud.net

import jr.brian.thorhud.model.Armory
import jr.brian.thorhud.model.ArmoryGroup
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Mods
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

    // Latest Los Santos Customs catalogue the plugin reported for the
    // current vehicle. Null until the user first opens Customs on a
    // vehicle that has a mod kit; cleared when the plugin reports a
    // vehicle change (so the stale list never bleeds into a new car).
    private val _mods = MutableStateFlow<Mods?>(null)
    val mods: StateFlow<Mods?> = _mods.asStateFlow()

    // Latest Ammu-Nation page from the plugin. Null until the user
    // long-presses the weapon card and the plugin sends the first
    // armory frame; cleared on hello (new game) or when the player
    // enters a vehicle (set_* commands refuse on four wheels, so the
    // sheet must close too).
    private val _armory = MutableStateFlow<Armory?>(null)
    val armory: StateFlow<Armory?> = _armory.asStateFlow()

    private val _lastUpdateMs = MutableStateFlow(0L)
    val lastUpdateMs: StateFlow<Long> = _lastUpdateMs.asStateFlow()

    // Last packed 0x00RRGGBB the companion sent via paint_rgb (color
    // wheel or swatch tap). The plugin itself never emits this on the
    // state frame, so if we didn't track it here the paint-to-lights
    // mirror would have nothing to color-match against. Null until
    // the first custom paint of the session.
    private val _lastPaintedRgb = MutableStateFlow<Long?>(null)
    val lastPaintedRgb: StateFlow<Long?> = _lastPaintedRgb.asStateFlow()

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
            _mods.value = null
            _armory.value = null
            _ack.value = null
            _last.value = GameState.WAITING
            _lastUpdateMs.value = 0L
            _hello.value = frame
            return
        }
        if (frame.isHello) _hello.value = frame
        if (frame.isState) {
            _state.value = frame
            // A state frame that says the player is on foot invalidates
            // the mods catalogue the plugin walked for the previous
            // vehicle; the plugin also sends a fresh zero-slot frame but
            // we drop ours a tick sooner so the UI doesn't flash the
            // old car's slots during the hop out.
            if (!frame.inVehicle) _mods.value = null
            // The player getting in a car invalidates the Ammu-Nation
            // page the plugin walked for them on foot. The plugin's
            // own apply refuses in-vehicle too, so dropping ours is
            // belt-and-braces.
            if (frame.inVehicle) _armory.value = null
        }
        if (frame.isAck) _ack.value = frame
        if (frame.isStations) _stations.value = frame.stations
        if (frame.isWeapons) _weapons.value = frame.weapons
        if (frame.isBlips) _blips.value = frame.blips
        if (frame.isMods) {
            // Zero-slot + zero-toggle frame means the plugin dropped
            // the catalogue (vehicle change / on foot). Keep the Mods
            // object around only when the vehicle still has a kit; the
            // screen uses null to mean "ask for a fresh list".
            _mods.value = if (frame.modsKit == true &&
                              (frame.modsSlots.isNotEmpty()
                               || frame.modsToggles.isNotEmpty())) {
                Mods(
                    ver = frame.listVersion ?: 0,
                    vehicleModel = frame.modsVehicleModel ?: 0L,
                    kit = true,
                    wheelType = frame.modsWheelType ?: 0,
                    tints = frame.tintsRaw ?: 0,
                    tint = frame.tintRaw ?: 0,
                    plateStyle = frame.modsPlateStyle ?: 0,
                    plate = frame.modsPlate.orEmpty(),
                    neon = frame.modsNeon == true,
                    bulletproof = frame.modsBulletproof == true,
                    slots = frame.modsSlots,
                    toggles = frame.modsToggles,
                )
            } else if (frame.modsKit == false) {
                // Vehicle without a mod kit: carry the fact so the
                // screen can show "this vehicle can't be customised".
                Mods(
                    ver = frame.listVersion ?: 0,
                    vehicleModel = frame.modsVehicleModel ?: 0L,
                    kit = false,
                    wheelType = 0, tints = 0, tint = 0,
                    plateStyle = 0, plate = "",
                    neon = false, bulletproof = false,
                    slots = emptyList(), toggles = emptyList(),
                )
            } else {
                null
            }
        }
        if (frame.isArmory) _armory.value = frame.toArmory()
        _last.value = frame
        _lastUpdateMs.value = System.currentTimeMillis()
    }

    /** Fold an "armory" frame into the [Armory] projection the sheet
     *  reads. Options are grouped by bone id, keeping their arrival
     *  order; each group's `curCidx` is the fitted non-default option
     *  if there is one, else -1 (which means only the stock default
     *  is present and the ◀ ▶ row shows "none / default / option 1
     *  / option 2 ..."). */
    private fun GameState.toArmory(): Armory {
        val grouped = armoryOptions
            .groupBy { it.bone }
            .map { (bone, opts) ->
                val cur = opts.firstOrNull { it.fitted && !it.isDefault }?.cidx ?: -1
                ArmoryGroup(bone = bone, curCidx = cur, options = opts)
            }
        return Armory(
            ver = listVersion ?: 0,
            widx = armoryWidx ?: -1,
            weaponHash = weaponHash ?: 0L,
            name = armoryName.orEmpty(),
            tints = tintsRaw ?: 0,
            tint = tintRaw ?: 0,
            ammo = armoryAmmo ?: 0,
            maxAmmo = armoryMaxAmmo ?: 0,
            clip = armoryClip ?: 0,
            groups = grouped,
        )
    }

    /** [weapon] is a GTA IV weapon id or a GTA V weapon hash; a hash
     *  needs the full unsigned 32-bit range, hence the Long. */
    suspend fun setWeapon(weapon: Long): Int? = client.send("set_weapon", weapon)
    suspend fun setRadio(stationIndex: Int): Int? =
        client.send("set_radio", stationIndex.toLong())

    /** Point the game's route at a blip from [blips]: GTA V sets the
     *  map waypoint on it, GTA IV turns its GPS route on. */
    suspend fun routeBlip(blipId: Int): Int? = client.send("route_blip", blipId.toLong())
    /** GTA V only: drop the player's map waypoint on arbitrary world
     *  xy coordinates. Used by the custom-map (atlas) page's long-press
     *  to route. The IV plugin refuses this command with
     *  "not in this game". */
    suspend fun waypointXy(x: Float, y: Float): Int? = client.sendXy("waypoint_xy", x, y)
    /** Undo [routeBlip] or [waypointXy]: remove the waypoint / turn
     *  the route off. */
    suspend fun routeClear(): Int? = client.send("route_clear", 0L)
    /** Ask the plugin to write its blip census to its log file. */
    suspend fun blipCensus(): Int? = client.send("blip_census", 0L)

    /** Run a cheat by id (see [jr.brian.thorhud.ui.cheats.CheatCatalog]).
     *  One-shots fire once; toggles flip and the next state frame's
     *  `cheats_mask` reports the new state. */
    suspend fun runCheat(cheatId: Int): Int? = client.send("cheat", cheatId.toLong())

    /** GTA V only: paint the current vehicle's two custom-colour layers
     *  to the exact RGB packed in [packed] as 0x00RRGGBB. GTA IV refuses
     *  the command with "not in this game"; the paint section in the UI
     *  hides the swatch row on IV so it is never sent there. Also
     *  records the pick in [lastPaintedRgb] so the paint-to-lights
     *  mirror has something to color-match against. */
    suspend fun paintRgb(packed: Long): Int? {
        _lastPaintedRgb.value = packed
        return client.send("paint_rgb", packed)
    }

    /** GTA V only. Customs commands; see
     *  jr.brian.thorhud.net.commandFrame for the arg-key mapping and
     *  asi-v/src/mods.cpp for the plugin side. Each returns the
     *  dispatched command id, or null if the client is not connected. */
    suspend fun requestMods(): Int? = client.send("mods_list", 0L)
    suspend fun setMod(slot: Int, index: Int): Int? {
        val raw = if (index < 0) 0xFFFF else (index and 0xFFFF)
        val packed = ((slot and 0xFFFF).toLong() shl 16) or raw.toLong()
        return client.send("set_mod", packed)
    }
    suspend fun toggleMod(slot: Int): Int? = client.send("toggle_mod", slot.toLong())
    suspend fun setWheelType(type: Int): Int? = client.send("set_wheel_type", type.toLong())
    suspend fun setTint(tint: Int): Int? = client.send("set_tint", tint.toLong())
    suspend fun setPlateStyle(style: Int): Int? =
        client.send("set_plate_style", style.toLong())
    suspend fun setPlate(text: String): Int? = client.sendText("set_plate", text)
    suspend fun setNeon(packedRgb: Long): Int? = client.send("set_neon", packedRgb)
    suspend fun setSmoke(packedRgb: Long): Int? = client.send("set_smoke", packedRgb)
    suspend fun setXenonColour(index: Int): Int? =
        client.send("set_xenon_colour", (if (index < 0) 0xFFFFFFFFL else index.toLong()))
    suspend fun setBulletproofTyres(on: Boolean): Int? =
        client.send("set_bulletproof_tyres", if (on) 1L else 0L)

    /** GTA V only. Ammu-Nation commands; see
     *  jr.brian.thorhud.net.commandFrame for the arg-key mapping and
     *  asi-v/src/armory.cpp for the plugin side. The sheet calls
     *  [requestArmory] on open and after each set_* lands; the plugin
     *  republishes with a bumped ver so the UI follows. */
    suspend fun requestArmory(weaponHash: Long): Int? =
        client.send("armory_list", weaponHash)
    suspend fun setComponent(widx: Int, cidx: Int, on: Boolean): Int? {
        val packed =
            ((widx and 0xFFFF).toLong() shl 16) or
            ((cidx and 0x7FFF).toLong() shl 1) or
            (if (on) 1L else 0L)
        return client.send("set_component", packed)
    }
    suspend fun setWeaponTint(widx: Int, tint: Int): Int? {
        val packed = ((widx and 0xFFFF).toLong() shl 16) or (tint and 0xFFFF).toLong()
        return client.send("set_weapon_tint", packed)
    }
    /** [action] 0 = fill to GET_MAX_AMMO; 1 = add one clip (clamped). */
    suspend fun setAmmo(widx: Int, action: Int): Int? {
        val packed = ((widx and 0xFFFF).toLong() shl 16) or (action and 0xFFFF).toLong()
        return client.send("set_ammo", packed)
    }

    /** Force a reconnect to the plugin; see [GameStateClient.reconnect]. */
    fun reconnect() = client.reconnect()
}
