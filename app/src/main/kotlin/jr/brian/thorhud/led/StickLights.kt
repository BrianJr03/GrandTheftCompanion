package jr.brian.thorhud.led

import kotlinx.coroutines.flow.StateFlow

/**
 * The Thor's two thumbstick light rings, as far as GTC is concerned:
 * one colour per stick, or "give them back".
 */
interface StickLights {
    /** What we know about whether commands can land. */
    val status: StateFlow<Status>

    /** Show [left] / [right] (ARGB) on the sticks. Also renews our hold
     *  on them; see [PoliceLights.SLOWEST_BEAT_MS]. */
    fun show(left: Int, right: Int)

    /** Hand the sticks back to whatever they were showing before. */
    fun clear()

    /** Re-check [status]. Only for when our colours are not up: the
     *  check hands the sticks back, like [clear]. */
    fun refreshStatus()

    enum class Status {
        /** Not checked yet. */
        Unknown,
        /** Bifrost is not installed. */
        NotInstalled,
        /** Bifrost is installed but "Allow third-party LED control" is off. */
        Disabled,
        /** Bifrost is installed but our command never reached it. */
        NoAnswer,
        /** Bifrost took the command. It still drops commands silently
         *  while its LED service is not running, and does not say so. */
        Ready;

        /** True when we know commands are going nowhere. */
        val isUnavailable: Boolean
            get() = this == NotInstalled || this == Disabled || this == NoAnswer
    }
}
