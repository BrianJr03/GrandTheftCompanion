package jr.brian.thorhud.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One frame emitted by ThorBridge.asi over 127.0.0.1:47801.
 *
 * Phase 1 defines three frame shapes, all discriminated by [type]:
 *   - "hello": sent once on connect; carries exe fingerprint + whether
 *              the ASI understands this build.
 *   - "tick":  20 Hz heartbeat, carries only a monotonic millisecond
 *              counter from GetTickCount().
 *   - "state": real game state; shape fleshed out in Phase 2.
 *
 * All fields other than [v] and [type] are optional and defaulted — the
 * JSON parser is configured with `ignoreUnknownKeys` so forward-compatible
 * frames don't break the client. The companion app treats a connection as
 * stale if no frame of any kind has arrived in the last ~1 second.
 */
@Serializable
data class GameState(
    @SerialName("v") val protocol: Int = 1,
    @SerialName("type") val type: String = "",

    @SerialName("crc32") val crc32: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("file_version") val fileVersion: String? = null,
    @SerialName("supported") val supported: Boolean? = null,

    @SerialName("t") val tickMs: Long? = null,

    @SerialName("in_game") val inGame: Boolean = false,
) {
    val isHello: Boolean get() = type == "hello"
    val isTick: Boolean get() = type == "tick"
    val isState: Boolean get() = type == "state"

    companion object {
        val WAITING = GameState(type = "waiting")
    }
}
