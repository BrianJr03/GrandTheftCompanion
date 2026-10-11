package jr.brian.thorhud

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity

/**
 * Trampoline launcher: finds the Thor's bottom display (any secondary
 * display with FLAG_PRESENTATION) and starts [HudActivity] on it via
 * [ActivityOptions.setLaunchDisplayId], then finishes itself so the
 * launcher icon never actually occupies the main screen.
 *
 * Falls back to launching [HudActivity] on the default display when no
 * secondary display exists (useful for dev builds on single-display
 * devices).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val targetDisplayId = pickBottomDisplayId(dm) ?: Display.DEFAULT_DISPLAY

        Log.i(TAG, "launching HudActivity on displayId=$targetDisplayId")

        val intent = Intent(this, HudActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplayId)
            .toBundle()
        startActivity(intent, options)
        finish()
    }

    private fun pickBottomDisplayId(dm: DisplayManager): Int? {
        // Any display beyond the default internal one with
        // FLAG_PRESENTATION is a candidate — on the AYN Thor this is
        // displayId=4 (local uniqueId local:4630946482288158084).
        return dm.displays
            .firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    (display.flags and Display.FLAG_PRESENTATION) != 0
            }
            ?.displayId
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
