package jr.brian.thorhud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** User choices that outlive the process. */
@Singleton
class GtcSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // On by default. It does nothing until the user has also allowed
    // third-party LED control inside Bifrost, which is the real opt-in.
    private val _policeLightsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_POLICE_LIGHTS, true))

    /** Flash the thumbsticks red and blue while wanted. */
    val policeLightsEnabled: StateFlow<Boolean> = _policeLightsEnabled.asStateFlow()

    fun setPoliceLightsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POLICE_LIGHTS, enabled).apply()
        _policeLightsEnabled.value = enabled
    }

    // Off by default. When on, every paint_rgb command (color wheel or
    // swatch tap) also drives the thumbstick lights to that same color.
    // Police lights still win while the player is wanted.
    private val _paintToLightsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_PAINT_TO_LIGHTS, false))

    /** Mirror the car's last custom paint to the sticks. */
    val paintToLightsEnabled: StateFlow<Boolean> = _paintToLightsEnabled.asStateFlow()

    fun setPaintToLightsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAINT_TO_LIGHTS, enabled).apply()
        _paintToLightsEnabled.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "gtc_settings"
        const val KEY_POLICE_LIGHTS = "police_lights_enabled"
        const val KEY_PAINT_TO_LIGHTS = "paint_to_lights_enabled"
    }
}
