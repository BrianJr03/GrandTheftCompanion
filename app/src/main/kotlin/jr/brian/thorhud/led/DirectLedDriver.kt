package jr.brian.thorhud.led

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import jr.brian.thorhud.led.StickLights.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
class DirectLedDriver @Inject constructor() : StickLights {

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

    // Whatever the SN3112 nodes were showing when we first grabbed
    // the sticks for this flash sequence. Captured once per override
    // (sysfs stays current between beats because we don't touch
    // state between show() calls), replayed on clear(). Null when
    // we're not currently overriding OR the sysfs reads failed.
    private var ambientSnapshot: String? = null
    private var overriding = false

    override fun show(left: Int, right: Int) {
        if (!overriding) {
            // The first beat of each sequence is our only chance to
            // see the user's resting colour before our own writes
            // overwrite it. Reads may silently fail (sysfs 0644 /
            // SELinux); null just means we fall back to black.
            ambientSnapshot = runCatching { snapshotAmbient() }.getOrNull()
            overriding = true
        }
        transact(Sn3112.dualCommand(left, right))
    }

    // Restore whatever the sticks were showing before this flash
    // sequence began. Nothing captured (sysfs unreadable, or we were
    // never overriding) falls through to black — the pre-hotfix
    // behaviour.
    override fun clear() {
        val snapshot = ambientSnapshot
        transact(snapshot ?: Sn3112.clearCommand())
        ambientSnapshot = null
        overriding = false
    }

    // No separate probe to run: binder is bound (or not) at init.
    override fun refreshStatus() = Unit

    /**
     * Read both SN3112 nodes and build a shell command that re-sends
     * exactly what they currently hold. Returns null when either
     * read fails (file permissions, missing device, SELinux denial),
     * which keeps the caller on the black-fallback path.
     */
    private fun snapshotAmbient(): String? {
        val left = File(Sn3112.LEFT_NODE).takeIf { it.canRead() }?.readText()
        val right = File(Sn3112.RIGHT_NODE).takeIf { it.canRead() }?.readText()
        if (left.isNullOrBlank() || right.isNullOrBlank()) return null
        return Sn3112.restoreCommand(left, right)
    }

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

    fun dualCommand(leftArgb: Int, rightArgb: Int): String {
        val lr = (leftArgb shr 16) and 0xFF
        val lg = (leftArgb shr 8) and 0xFF
        val lb = leftArgb and 0xFF
        val rr = (rightArgb shr 16) and 0xFF
        val rg = (rightArgb shr 8) and 0xFF
        val rb = rightArgb and 0xFF
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

    fun clearCommand(): String = dualCommand(0, 0)

    /**
     * Rebuild an echo-chain that re-writes whatever [left] and
     * [right] were just read from the sysfs nodes. The kernel driver
     * accepts the same `zone-R:G:B:A` lines it emits on read, so no
     * parsing is needed — any blank or `#`-prefixed lines are
     * dropped (defensive against drivers that annotate reads).
     */
    fun restoreCommand(left: String, right: String): String = buildString(256) {
        appendZones(left, LEFT_NODE)
        appendZones(right, RIGHT_NODE)
    }.ifBlank { clearCommand() }

    private fun StringBuilder.appendZones(raw: String, node: String) {
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (isNotEmpty()) append(" && ")
            append("echo ").append(trimmed).append(" > ").append(node)
        }
    }
}
