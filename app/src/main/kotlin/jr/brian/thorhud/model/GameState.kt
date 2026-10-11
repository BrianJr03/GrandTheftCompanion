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
 * Fields beyond [v] and [type] are optional and nullable, parser is
 * configured with `ignoreUnknownKeys` so new fields never break the client.
 */
@Serializable
data class GameState(
    @SerialName("v") val protocol: Int = 1,
    @SerialName("type") val type: String = "",

    // hello
    @SerialName("crc32") val crc32: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("file_version") val fileVersion: String? = null,
    @SerialName("supported") val supported: Boolean? = null,

    // tick + state (as monotonic ms since game load)
    @SerialName("t") val tickMs: Long? = null,

    // state — core
    @SerialName("in_game") val inGame: Boolean = false,
    @SerialName("in_vehicle") val inVehicle: Boolean = false,
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
) {
    val isHello: Boolean get() = type == "hello"
    val isTick: Boolean get() = type == "tick"
    val isState: Boolean get() = type == "state"

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
    )

    companion object {
        val WAITING = GameState(type = "waiting")
    }
}
