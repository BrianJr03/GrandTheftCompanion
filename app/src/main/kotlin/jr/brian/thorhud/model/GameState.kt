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
    @SerialName("owned") val ownedWeapons: List<OwnedWeapon> = emptyList(),

    // state — vehicle
    @SerialName("vehicle") val vehicle: Vehicle? = null,

    // state — audio
    @SerialName("radio") val radio: Int? = null,

    // state — episode. 0 = GTA IV, 1 = The Lost and Damned, 2 = TBoGT.
    // Drives the HUD's accent palette.
    @SerialName("episode") val episode: Int? = null,

    // ---- GTA V only ------------------------------------------------
    // Session flags. `stalled` = the plugin is alive but the game has
    // stopped running scripts (pause menu, loading); shown like paused.
    @SerialName("online") val online: Boolean = false,
    @SerialName("stalled") val stalled: Boolean = false,
    @SerialName("switching") val switching: Boolean = false,
    @SerialName("cutscene") val cutscene: Boolean = false,
    @SerialName("mission") val mission: Boolean = false,
    @SerialName("animal") val animal: Boolean = false,

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

    // "stations" / "weapons" list frames.
    @SerialName("stations") val stations: List<Station> = emptyList(),
    @SerialName("weapons") val weapons: List<VWeapon> = emptyList(),

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
    val isGtaV: Boolean get() = game == GAME_V

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
