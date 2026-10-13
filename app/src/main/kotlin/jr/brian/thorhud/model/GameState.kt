package jr.brian.thorhud.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One frame emitted by ThorBridge.asi over 127.0.0.1:47801.
 *
 * Frame types (discriminated by [type]):
 *   - "hello":   sent once on connect; carries exe fingerprint + whether
 *                the ASI understands this build.
 *   - "state":   full game-state snapshot published by the game-thread
 *                hook. See [in_game]/[inVehicle]/stats/weapon/vehicle/radio.
 *   - "tick":    heartbeat emitted when the ASI is up but has no snapshot
 *                yet (menu, loading, pre-init).
 *
 *
 * GTA V (ThorBridgeV.asi) speaks the same protocol on the same port and
 * adds `"game":"v"` to every frame, plus:
 *   - "stations": the game's radio-station list (sent on connect and
 *                 whenever it changes). See [stations].
 *   - "weapons":  the weapons the current character owns. See [weapons].
 * Its extra `state` fields are grouped under "GTA V only" below. GTA IV
 * frames simply never carry them, so one model serves both games.
 *
 * Both plugins (ThorBridgeV 0.3.0+, ThorBridge at natives level 5) add:
 *   - "blips":    the map's blips, nearest first, re-sent whenever the
 *                 plugin's census changes. See [blips] and [Blip].
 * and route-related `state` fields: [waypoint] (GTA V), [routeBlip] and
 * [mission] (GTA IV).
 *
 * Fields beyond [v] and [type] are optional and nullable, parser is
 * configured with `ignoreUnknownKeys` so new fields never break the client.
 */
@Serializable
data class GameState(
    @SerialName("v") val protocol: Int = 1,
    @SerialName("type") val type: String = "",

    // Which game the plugin is running in. Absent (null) = GTA IV,
    // "v" = GTA V. Drives which HUD layout is shown.
    @SerialName("game") val game: String? = null,

    // hello
    @SerialName("crc32") val crc32: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("file_version") val fileVersion: String? = null,
    @SerialName("supported") val supported: Boolean? = null,
    // hello — GTA V only: host exe name, plugin version, and whether
    // Script Hook V was found. When `supported` is false, [err]
    // carries the reason.
    @SerialName("exe") val exe: String? = null,
    @SerialName("plugin") val plugin: String? = null,
    @SerialName("shv") val shv: Boolean? = null,

    // tick + state (as monotonic ms since game load)
    @SerialName("t") val tickMs: Long? = null,

    // state — core
    @SerialName("in_game") val inGame: Boolean = false,
    @SerialName("in_vehicle") val inVehicle: Boolean = false,
    @SerialName("paused") val paused: Boolean = false,
    @SerialName("money") val money: Long? = null,
    @SerialName("health") val health: Float? = null,
    @SerialName("max_health") val maxHealth: Int? = null,
    @SerialName("armor") val armor: Int? = null,
    @SerialName("max_armor") val maxArmor: Int? = null,
    @SerialName("wanted") val wanted: Int? = null,

    // state — weapon
    @SerialName("weapon") val weaponId: Int? = null,
    @SerialName("ammo_total") val ammoTotal: Int? = null,
    @SerialName("ammo_clip") val ammoClip: Int? = null,
    // GTA IV: what the player carries, one weapon per game slot. Empty
    // unless the plugin was built at natives level 4.
    @SerialName("owned") val ownedWeapons: List<OwnedWeapon> = emptyList(),

    // state — vehicle
    @SerialName("vehicle") val vehicle: Vehicle? = null,

    // state — audio
    @SerialName("radio") val radio: Int? = null,

    // state — episode. 0 = GTA IV, 1 = The Lost and Damned, 2 = TBoGT.
    // Drives the HUD's accent palette.
    @SerialName("episode") val episode: Int? = null,

    // state — GTA IV: the blip the companion last pointed the game's
    // GPS route at (`route_blip` command), 0 = none. Natives level 5.
    @SerialName("route_blip") val routeBlip: Int? = null,

    // ---- GTA V only ------------------------------------------------
    // Session flags. `stalled` = the plugin is alive but the game has
    // stopped running scripts (pause menu, loading); shown like paused.
    @SerialName("online") val online: Boolean = false,
    @SerialName("stalled") val stalled: Boolean = false,
    @SerialName("switching") val switching: Boolean = false,
    @SerialName("cutscene") val cutscene: Boolean = false,
    @SerialName("mission") val mission: Boolean = false,
    @SerialName("animal") val animal: Boolean = false,
    // The player is holding the game's weapon-wheel control (LB / L1 /
    // Tab) on foot, so the companion shows its own wheel. Plugin 0.2.0+;
    // older plugins never send it and the wheel is opened by tap only.
    // GTA IV sends the same flag for LB / L1 (it has no wheel of its
    // own) from a plugin built at natives level 4.
    @SerialName("wheel") val wheel: Boolean = false,

    // 0 = Michael, 1 = Franklin, 2 = Trevor, -1 = anyone else
    // (Director Mode actor, animal). Drives the accent palette the way
    // [episode] does for GTA IV.
    @SerialName("character") val character: Int? = null,

    @SerialName("special") val special: Special? = null,

    // GTA V weapon ids are 32-bit name hashes that overflow a signed
    // Int, so they travel under their own key rather than "weapon".
    @SerialName("weapon_hash") val weaponHash: Long? = null,
    @SerialName("weapon_name") val weaponName: String? = null,
    @SerialName("clip_size") val clipSize: Int? = null,

    // Station id ("RADIO_01_CLASS_ROCK"); "" when the radio is off.
    // GTA V stations are matched by this name, never by [radio] index:
    // the index order differs between game builds.
    @SerialName("radio_name") val radioName: String? = null,

    // state — GTA V: the player's map waypoint. See [Waypoint].
    @SerialName("waypoint") val waypoint: Waypoint? = null,

    // "stations" / "weapons" / "blips" list frames.
    @SerialName("stations") val stations: List<Station> = emptyList(),
    @SerialName("weapons") val weapons: List<VWeapon> = emptyList(),
    @SerialName("blips") val blips: List<Blip> = emptyList(),
    // "blips": the plugin's version counter for the list.
    @SerialName("ver") val listVersion: Int? = null,

    // ack (Phase 3) — correlation id matches the cmd id, ok=true on
    // success, err carries a short reason string on failure.
    @SerialName("id") val ackId: Int? = null,
    @SerialName("ok") val ok: Boolean? = null,
    @SerialName("err") val err: String? = null,
) {
    val isHello: Boolean get() = type == "hello"
    val isTick: Boolean get() = type == "tick"
    val isState: Boolean get() = type == "state"
    val isAck: Boolean get() = type == "ack"
    val isStations: Boolean get() = type == "stations"
    val isWeapons: Boolean get() = type == "weapons"
    val isBlips: Boolean get() = type == "blips"
    val isGtaV: Boolean get() = game == GAME_V

    /** The blip id the game is currently routing to on the companion's
     *  behalf, or null. GTA V: the waypoint it set and that the player
     *  has not moved since. GTA IV: the blip whose GPS route it turned
     *  on. */
    val routedBlipId: Int?
        get() = when {
            waypoint != null -> waypoint.blip.takeIf { waypoint.active && waypoint.ours && it != 0 }
            else -> routeBlip?.takeIf { it != 0 }
        }

    @Serializable
    data class OwnedWeapon(
        @SerialName("slot") val slot: Int,
        @SerialName("id") val id: Int,
        @SerialName("ammo") val ammo: Int,
    )

    @Serializable
    data class Vehicle(
        @SerialName("model") val model: Long = 0,
        @SerialName("name") val name: String = "",
        @SerialName("speed_mps") val speedMps: Float = 0f,
        @SerialName("engine_hp") val engineHp: Float = 0f,
        // GTA V only. `vehicleClass` is GET_VEHICLE_CLASS (0..22).
        @SerialName("class") val vehicleClass: Int? = null,
        @SerialName("body_hp") val bodyHp: Float? = null,
        @SerialName("altitude_m") val altitudeM: Float? = null,
        @SerialName("has_radio") val hasRadio: Boolean? = null,
    )

    /** GTA V special-ability state. The game exposes no meter level to
     *  scripts, only these flags. */
    @Serializable
    data class Special(
        @SerialName("unlocked") val unlocked: Boolean = false,
        @SerialName("enabled") val enabled: Boolean = false,
        @SerialName("active") val active: Boolean = false,
        @SerialName("full") val full: Boolean = false,
    )

    /** One entry of the GTA V radio-station list. [label] is the game's
     *  own localised name and may be empty. The entry's position in
     *  the list is what `set_radio` takes. */
    @Serializable
    data class Station(
        @SerialName("name") val name: String = "",
        @SerialName("label") val label: String = "",
    )

    /**
     * One blip on the game's map, as the plugin's census saw it. Raw:
     * [sprite], [colour], [blipType] and [display] are the game's own
     * enums. Which sprites are mission starts is being worked out from
     * this data on-device; see ui/map/BlipLabels.kt for the current
     * (provisional) names.
     */
    @Serializable
    data class Blip(
        @SerialName("id") val id: Int = 0,
        @SerialName("sprite") val sprite: Int = 0,
        @SerialName("colour") val colour: Int = 0,
        @SerialName("type") val blipType: Int = 0,
        @SerialName("display") val display: Int = 0,
        @SerialName("x") val x: Float = 0f,
        @SerialName("y") val y: Float = 0f,
        @SerialName("z") val z: Float = 0f,
        @SerialName("dist_m") val distM: Float = 0f,
    )

    /**
     * GTA V: the player's map waypoint. [active] whoever set it; [ours]
     * when the companion set it (via `route_blip`) and it has not been
     * moved since, in which case [blip] is the blip it points at. A
     * hand-placed waypoint is never [ours], so the companion leaves
     * it alone.
     */
    @Serializable
    data class Waypoint(
        @SerialName("active") val active: Boolean = false,
        @SerialName("ours") val ours: Boolean = false,
        @SerialName("blip") val blip: Int = 0,
    )

    /** One weapon the current GTA V character owns. */
    @Serializable
    data class VWeapon(
        @SerialName("hash") val hash: Long = 0,
        @SerialName("group") val group: Long = 0,
        @SerialName("ammo") val ammo: Int = 0,
        @SerialName("name") val name: String = "",
    )

    companion object {
        val WAITING = GameState(type = "waiting")
        const val GAME_V = "v"
    }
}
