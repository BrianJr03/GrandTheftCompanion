package jr.brian.thorhud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jr.brian.thorhud.ui.components.TabArrowIconStyle
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

    // On by default. The first-launch sheet gives the user a chance
    // to turn them off straight away ("Not now" leaves them on? no —
    // see InfoSheet wiring: Enable flips on, Not now dismisses without
    // touching the pref, so the default lands). After the one-shot
    // sheet the App section in Settings is the only path to flip the
    // pref. Pre-pager states (disconnected, V hard-notice) hide the
    // arrows regardless because the pager isn't drawn.
    private val _tabArrowsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TAB_ARROWS, true))

    /** Sticky left/right chevrons on the pager for one-tap tab moves. */
    val tabArrowsEnabled: StateFlow<Boolean> = _tabArrowsEnabled.asStateFlow()

    fun setTabArrowsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TAB_ARROWS, enabled).apply()
        _tabArrowsEnabled.value = enabled
    }

    /** True once the first-launch opt-in sheet for tab arrows has been
     *  shown. One shot per installation; the sheet never auto-opens
     *  after this flips. */
    var hasSeenTabArrowsPrompt: Boolean
        get() = prefs.getBoolean(KEY_SEEN_TAB_ARROWS_PROMPT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN_TAB_ARROWS_PROMPT, value).apply()
        }

    // Icon style for the sticky tab-nav buttons. Backed by the enum
    // name so an older build that doesn't know a style falls back to
    // the Arrows default rather than crashing.
    private val _tabArrowIconStyle = MutableStateFlow(loadTabArrowIconStyle())

    /** Which glyph to paint on each tab-nav button (arrows / stars /
     *  transparent). Collected by MainScreen; the Settings App section
     *  flips it with a segmented row. */
    val tabArrowIconStyle: StateFlow<TabArrowIconStyle> = _tabArrowIconStyle.asStateFlow()

    fun setTabArrowIconStyle(style: TabArrowIconStyle) {
        prefs.edit().putString(KEY_TAB_ARROWS_STYLE, style.name).apply()
        _tabArrowIconStyle.value = style
    }

    private fun loadTabArrowIconStyle(): TabArrowIconStyle {
        val raw = prefs.getString(KEY_TAB_ARROWS_STYLE, null)
        return TabArrowIconStyle.entries.firstOrNull { it.name == raw }
            ?: TabArrowIconStyle.Arrows
    }

    private companion object {
        const val PREFS_NAME = "gtc_settings"
        const val KEY_POLICE_LIGHTS = "police_lights_enabled"
        const val KEY_PAINT_TO_LIGHTS = "paint_to_lights_enabled"
        const val KEY_TAB_ARROWS = "tab_arrows_enabled"
        const val KEY_SEEN_TAB_ARROWS_PROMPT = "seen_tab_arrows_prompt"
        const val KEY_TAB_ARROWS_STYLE = "tab_arrows_icon_style"
    }
}
