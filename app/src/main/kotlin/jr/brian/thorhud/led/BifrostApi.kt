package jr.brian.thorhud.led

/**
 * String contract of Bifrost's third-party LED API
 * (https://github.com/Pollux-MoonBench/Bifrost, INTEGRATING.md). Bifrost
 * ships no library for this; callers copy the constants. Only what GTC
 * sends is listed here.
 *
 * GTC never touches the Thor's LED hardware itself. Bifrost owns the
 * sticks, and layers our colours over whatever it was showing, then
 * puts that back when we clear.
 */
internal object BifrostApi {
    const val PERMISSION = "com.moonbench.bifrost.permission.CONTROL_LEDS"
    const val RECEIVER_PACKAGE = "com.moonbench.bifrost"
    const val RECEIVER_CLASS = "com.moonbench.bifrost.external.ExternalApiReceiver"

    const val ACTION_DISPLAY = "com.moonbench.bifrost.api.ACTION_DISPLAY"
    const val ACTION_CLEAR = "com.moonbench.bifrost.api.ACTION_CLEAR"

    const val API_VERSION = 1
    const val EXTRA_API_VERSION = "apiVersion"   // Int, on every command

    const val EXTRA_EFFECT = "effect"            // String
    const val EXTRA_COLOR = "color"              // Int ARGB, left stick
    const val EXTRA_COLOR_RIGHT = "colorRight"   // Int ARGB, right stick
    const val EXTRA_INTENSITY = "intensity"      // Int 0..255
    const val EXTRA_SMOOTHNESS = "smoothness"    // Float 0..1
    const val EXTRA_PRIORITY = "priority"        // Int 0..100, default 50
    const val EXTRA_UNTIL = "until"              // String

    const val EFFECT_STATIC = "STATIC"
    const val UNTIL_EXPLICIT_CLEAR = "EXPLICIT_CLEAR"

    // Result codes of an ordered broadcast.
    const val RESULT_ACCEPTED = 0
    const val RESULT_REJECTED_DISABLED = -1      // user has third-party control off
}
