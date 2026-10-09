package jr.brian.thorhud.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One frame emitted by ThorBridge.asi over 127.0.0.1:47801.
 *
 * The ASI serializes a compact JSON object per 50 ms tick. `valid` is false
 * before GTA IV has loaded a player (title screen, loading). All other
 * fields are only meaningful when `valid` is true.
 */
@Serializable
data class GameState(
    @SerialName("t") val tickMs: Long,
    @SerialName("valid") val valid: Boolean = false,
    @SerialName("health") val health: Float = 0f,
    @SerialName("max_health") val maxHealth: Int = 0,
    @SerialName("max_armor") val maxArmor: Int = 0,
) {
    companion object {
        val WAITING = GameState(tickMs = 0, valid = false)
    }
}
