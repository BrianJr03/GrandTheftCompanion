package jr.brian.thorhud.led

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import jr.brian.thorhud.led.StickLights.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StickLights] by way of Bifrost's broadcast API. Every command is an
 * explicit broadcast to Bifrost's receiver; nothing here blocks.
 *
 * All of it is best-effort. Without Bifrost, with third-party control
 * switched off, or with Bifrost's LED service stopped, the commands go
 * nowhere and the HUD carries on without lights.
 */
@Singleton
class BifrostClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : StickLights {

    private val _status = MutableStateFlow(Status.Unknown)
    override val status: StateFlow<Status> = _status.asStateFlow()

    override fun show(left: Int, right: Int) = send(displayIntent(left, right))

    override fun clear() = send(command(BifrostApi.ACTION_CLEAR))

    // Bifrost has no "are you there" command. A clear is the cheapest
    // one that still runs its permission gate, and does nothing when we
    // hold no override.
    override fun refreshStatus() = clear()

    /**
     * Everything goes out as an ordered broadcast, for two reasons:
     * Android then delivers our commands in the order we sent them (a
     * clear can never overtake the colour before it), and each one
     * comes back with a result code that keeps [status] current.
     */
    private fun send(intent: Intent) {
        runCatching {
            context.sendOrderedBroadcast(
                intent,
                BifrostApi.PERMISSION,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, result: Intent) {
                        onResult(resultCode)
                    }
                },
                null,
                RESULT_UNANSWERED,
                null,
                null,
            )
        }.onFailure { Log.w(TAG, "send failed", it) }
    }

    private fun onResult(resultCode: Int) {
        val answered = statusFor(resultCode)
        when {
            answered != null -> _status.value = answered
            resultCode == RESULT_UNANSWERED ->
                _status.value = if (isInstalled()) Status.NoAnswer else Status.NotInstalled
            // Anything else (rate-limited, version mismatch): Bifrost is
            // there and we learned nothing new about it.
        }
    }

    // Needs the <queries> entry in the manifest to see Bifrost at all.
    private fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(BifrostApi.RECEIVER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    internal companion object {
        private const val TAG = "BifrostClient"

        // Above Bifrost's default (50) so an ambient effect from another
        // app does not take the sticks mid-chase; below the 80+ band
        // Bifrost keeps for urgent alerts.
        const val PRIORITY = 70

        // Seeds the ordered broadcast's result; still there afterwards
        // if no receiver ran. Bifrost's "disabled" is -1, the same
        // number as Activity.RESULT_OK, so the usual seed would read as
        // "disabled" whenever nothing answered at all.
        const val RESULT_UNANSWERED = Int.MIN_VALUE

        fun command(action: String): Intent = Intent(action).apply {
            setClassName(BifrostApi.RECEIVER_PACKAGE, BifrostApi.RECEIVER_CLASS)
            putExtra(BifrostApi.EXTRA_API_VERSION, BifrostApi.API_VERSION)
        }

        fun displayIntent(left: Int, right: Int): Intent =
            command(BifrostApi.ACTION_DISPLAY).apply {
                // STATIC, re-sent with the colours swapped, rather than
                // Bifrost's STROBE: STROBE blinks both sticks on and off
                // together many times a second and its rate cannot be
                // changed once running. A repeat STATIC from the same
                // app is applied to the running effect in place.
                putExtra(BifrostApi.EXTRA_EFFECT, BifrostApi.EFFECT_STATIC)
                putExtra(BifrostApi.EXTRA_COLOR, left)
                putExtra(BifrostApi.EXTRA_COLOR_RIGHT, right)
                putExtra(BifrostApi.EXTRA_INTENSITY, 255)
                // Highest smoothness is Bifrost's quickest colour change.
                putExtra(BifrostApi.EXTRA_SMOOTHNESS, 1f)
                putExtra(BifrostApi.EXTRA_PRIORITY, PRIORITY)
                // Not a duration: Bifrost arms a duration once, on the
                // first command, and would cut a long chase short.
                putExtra(BifrostApi.EXTRA_UNTIL, BifrostApi.UNTIL_EXPLICIT_CLEAR)
            }

        /** The status a result code proves, or null when it proves nothing. */
        fun statusFor(resultCode: Int): Status? = when (resultCode) {
            BifrostApi.RESULT_ACCEPTED -> Status.Ready
            BifrostApi.RESULT_REJECTED_DISABLED -> Status.Disabled
            else -> null
        }
    }
}
