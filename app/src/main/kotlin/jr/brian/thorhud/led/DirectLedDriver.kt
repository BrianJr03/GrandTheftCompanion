package jr.brian.thorhud.led

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import jr.brian.thorhud.led.StickLights.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// The sysfs-shell + PServerBinder path is derived from Pollux-MoonBench's
// Bifrost (archived), tools/LedController.kt. GPL-3.0, attributed in
// app/NOTICE. We keep only the "draw one frame on both sticks" slice —
// no master-fade baseline, no 16 ms de-dup — because PoliceLights'
// beat loop is the animation here.

/**
 * [StickLights] by writing straight to the Thor's SN3112 LED kernel
 * nodes through its `PServerBinder` vendor service. No Bifrost
 * involved; works on an AYN Thor whether Bifrost is installed or not.
 *
 * Available only when running on an AYN Thor and the binder resolves.
 * Everywhere else [isAvailable] is false, [show]/[clear] are no-ops,
 * and [AppModule] falls through to [BifrostClient].
 */
@Singleton
class DirectLedDriver @Inject constructor(
    @ApplicationContext private val context: Context,
) : StickLights {

    // PServerBinder is hidden API; reflection is the only way for a
    // regular app. On non-Thor devices "PServerBinder" is not a known
    // service name and getService returns null — safe to call anywhere.
    private val binder: IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(null, "PServerBinder") as? IBinder
    }.getOrNull()

    private val _status = MutableStateFlow(
        if (DeviceInfo.isAynThor && binder != null) Status.Ready else Status.NotInstalled
    )
    override val status: StateFlow<Status> = _status.asStateFlow()

    /** True when a real binder is held; the controller uses this to
     *  prefer the direct path over Bifrost. */
    fun isAvailable(): Boolean = _status.value == Status.Ready

    // Nothing stored between calls — we re-read Settings.System on
    // every beat and every clear. A user who re-picks mid-chase sees
    // the new brightness on the next flash and the new colour the
    // moment heat ends. The sysfs path is write-only, so this is the
    // only live source of truth.
    override fun show(left: Int, right: Int) {
        val b = JoystickColorSetting.read(context)?.brightness ?: Sn3112.BRIGHTNESS
        transact(Sn3112.dualCommand(left, right, b))
    }

    override fun clear() {
        val snap = JoystickColorSetting.read(context)
        val cmd = if (snap != null) {
            Sn3112.dualCommand(snap.left, snap.right, snap.brightness)
        } else {
            Sn3112.clearCommand()
        }
        transact(cmd)
    }

    // No separate probe to run: binder is bound (or not) at init.
    override fun refreshStatus() = Unit

    private fun transact(command: String) {
        val b = binder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // Bifrost's PServer protocol: writeStringArray([cmd, "1"]),
            // transact(code=0, ONEWAY). The "1" is PServer's "run as
            // root" switch; without it the write is dropped silently.
            data.writeStringArray(arrayOf(command, "1"))
            b.transact(0, data, reply, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "LED transact failed", t)
            _status.value = Status.NoAnswer
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "DirectLedDriver"
    }
}

/**
 * Shell strings for the SN3112 LED kernel driver on the AYN Thor. Pure
 * so the test suite can pin the byte-exact wire format.
 *
 *   /sys/class/sn3112l/led/brightness   — left stick  (zones 1 + 2)
 *   /sys/class/sn3112r/led/brightness   — right stick (zones 1 + 2)
 *   format: "<zone>-<R>:<G>:<B>:<brightness>"
 *
 * Each stick has a top and a bottom zone; both get the same colour so
 * the ring reads as one solid light.
 */
internal object Sn3112 {
    const val LEFT_NODE = "/sys/class/sn3112l/led/brightness"
    const val RIGHT_NODE = "/sys/class/sn3112r/led/brightness"
    const val BRIGHTNESS = 255

    // Brightness attenuates the RGB values, not the sysfs brightness
    // byte. The vendor daemon appears to work the same way — matching
    // its wire format is what makes our restore visually identical to
    // the pre-flash resting state, instead of a bit brighter.
    fun dualCommand(leftArgb: Int, rightArgb: Int, brightness: Int = BRIGHTNESS): String {
        val b = brightness.coerceIn(0, 255)
        val lr = scale((leftArgb shr 16) and 0xFF, b)
        val lg = scale((leftArgb shr 8) and 0xFF, b)
        val lb = scale(leftArgb and 0xFF, b)
        val rr = scale((rightArgb shr 16) and 0xFF, b)
        val rg = scale((rightArgb shr 8) and 0xFF, b)
        val rb = scale(rightArgb and 0xFF, b)
        return buildString(220) {
            append("echo 1-").append(lr).append(':').append(lg).append(':').append(lb).append(':').append(BRIGHTNESS)
            append(" > ").append(LEFT_NODE)
            append(" && echo 2-").append(lr).append(':').append(lg).append(':').append(lb).append(':').append(BRIGHTNESS)
            append(" > ").append(LEFT_NODE)
            append(" && echo 1-").append(rr).append(':').append(rg).append(':').append(rb).append(':').append(BRIGHTNESS)
            append(" > ").append(RIGHT_NODE)
            append(" && echo 2-").append(rr).append(':').append(rg).append(':').append(rb).append(':').append(BRIGHTNESS)
            append(" > ").append(RIGHT_NODE)
        }
    }

    private fun scale(channel: Int, brightness: Int): Int = (channel * brightness) / 255

    fun clearCommand(): String = dualCommand(0, 0)
}

/**
 * Reads AYN control-lite's joystick picker settings:
 *   joystick_led_light_picker_color  — `#AARRGGBB,#AARRGGBB` (left,right)
 *   led_light_brightness_percent     — float 0.0..1.0
 * Missing or malformed colour returns null; missing brightness defaults
 * to full so a user who's never touched the slider still gets a bright
 * restore.
 */
internal object JoystickColorSetting {
    const val COLOR_KEY = "joystick_led_light_picker_color"
    const val BRIGHTNESS_KEY = "led_light_brightness_percent"
    const val DEFAULT_BRIGHTNESS = 255

    data class Snapshot(val left: Int, val right: Int, val brightness: Int)

    fun read(context: Context): Snapshot? {
        val cr = context.contentResolver
        val colors = parseColors(Settings.System.getString(cr, COLOR_KEY)) ?: return null
        val brightness = parseBrightness(Settings.System.getString(cr, BRIGHTNESS_KEY))
            ?: DEFAULT_BRIGHTNESS
        return Snapshot(colors.first, colors.second, brightness)
    }

    fun parseColors(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val left = parseArgb(parts[0]) ?: return null
        val right = parseArgb(parts[1]) ?: return null
        return left to right
    }

    fun parseBrightness(raw: String?): Int? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val pct = raw.trim().toDoubleOrNull() ?: return null
        return (pct.coerceIn(0.0, 1.0) * 255).toInt()
    }

    private fun parseArgb(token: String): Int? {
        val t = token.trim()
        if (!t.startsWith('#') || t.length != 9) return null
        return runCatching { t.substring(1).toLong(16).toInt() }.getOrNull()
    }
}
