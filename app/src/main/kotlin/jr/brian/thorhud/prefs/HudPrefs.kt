package jr.brian.thorhud.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HudPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last-selected pager page per game (0 = HUD, 1 = Map). */
    fun pagerPage(gameKey: String): Int =
        prefs.getInt("${KEY_PAGER_PAGE}_$gameKey", 0)

    fun setPagerPage(gameKey: String, page: Int) {
        prefs.edit().putInt("${KEY_PAGER_PAGE}_$gameKey", page).apply()
    }

    /** True after the user has dismissed the one-time swipe-hint dialog. */
    var hasSeenSwipeHint: Boolean
        get() = prefs.getBoolean(KEY_SEEN_SWIPE_HINT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN_SWIPE_HINT, value).apply()
        }

    /** Last latestVersion the user dismissed the auto-popup for. The
     *  auto-open on app start skips any version that matches this; the
     *  status-bar chip still opens the dialog on demand. Blank until
     *  the user has dismissed anything. */
    var dismissedUpdateVersion: String
        get() = prefs.getString(KEY_DISMISSED_UPDATE_VERSION, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, value).apply()
        }

    /** User zoom factor on top of the preset mirror crop (1.0 = preset). */
    fun mirrorScale(gameKey: String): Float =
        prefs.getFloat("${KEY_MIRROR_SCALE}_$gameKey", 1f)

    /** User pan on top of the preset crop, in source-image fractions. */
    fun mirrorOffsetX(gameKey: String): Float =
        prefs.getFloat("${KEY_MIRROR_OFFSET_X}_$gameKey", 0f)

    fun mirrorOffsetY(gameKey: String): Float =
        prefs.getFloat("${KEY_MIRROR_OFFSET_Y}_$gameKey", 0f)

    fun setMirrorTransform(gameKey: String, scale: Float, offsetX: Float, offsetY: Float) {
        prefs.edit()
            .putFloat("${KEY_MIRROR_SCALE}_$gameKey", scale)
            .putFloat("${KEY_MIRROR_OFFSET_X}_$gameKey", offsetX)
            .putFloat("${KEY_MIRROR_OFFSET_Y}_$gameKey", offsetY)
            .apply()
    }

    fun resetMirrorTransform(gameKey: String) {
        prefs.edit()
            .remove("${KEY_MIRROR_SCALE}_$gameKey")
            .remove("${KEY_MIRROR_OFFSET_X}_$gameKey")
            .remove("${KEY_MIRROR_OFFSET_Y}_$gameKey")
            .apply()
    }

    // StateFlow-backed so Compose redraws immediately when the user
    // flips a switch in the settings sheet. The prefs file is still
    // the source of truth on next launch.

    private val _autoSwipeOnDrive =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_SWIPE_ON_DRIVE, false))
    val autoSwipeOnDrive: StateFlow<Boolean> = _autoSwipeOnDrive.asStateFlow()
    fun setAutoSwipeOnDrive(enabled: Boolean) {
        _autoSwipeOnDrive.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SWIPE_ON_DRIVE, enabled).apply()
    }

    private val _autoStartMirror =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_START_MIRROR, false))
    val autoStartMirror: StateFlow<Boolean> = _autoStartMirror.asStateFlow()
    fun setAutoStartMirror(enabled: Boolean) {
        _autoStartMirror.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_START_MIRROR, enabled).apply()
    }

    private val _alwaysReturnToHud =
        MutableStateFlow(prefs.getBoolean(KEY_ALWAYS_RETURN_TO_HUD, false))
    val alwaysReturnToHud: StateFlow<Boolean> = _alwaysReturnToHud.asStateFlow()
    fun setAlwaysReturnToHud(enabled: Boolean) {
        _alwaysReturnToHud.value = enabled
        prefs.edit().putBoolean(KEY_ALWAYS_RETURN_TO_HUD, enabled).apply()
    }

    private val _mapControlsRight =
        MutableStateFlow(prefs.getBoolean(KEY_MAP_CONTROLS_RIGHT, false))
    val mapControlsRight: StateFlow<Boolean> = _mapControlsRight.asStateFlow()
    fun setMapControlsRight(enabled: Boolean) {
        _mapControlsRight.value = enabled
        prefs.edit().putBoolean(KEY_MAP_CONTROLS_RIGHT, enabled).apply()
    }

    private companion object {
        const val FILE = "hud_prefs"
        const val KEY_PAGER_PAGE = "pager_page"
        const val KEY_SEEN_SWIPE_HINT = "seen_swipe_hint"
        const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
        const val KEY_MIRROR_SCALE = "mirror_scale"
        const val KEY_MIRROR_OFFSET_X = "mirror_offset_x"
        const val KEY_MIRROR_OFFSET_Y = "mirror_offset_y"
        const val KEY_AUTO_SWIPE_ON_DRIVE = "auto_swipe_on_drive"
        const val KEY_AUTO_START_MIRROR = "auto_start_mirror"
        const val KEY_ALWAYS_RETURN_TO_HUD = "always_return_to_hud"
        const val KEY_MAP_CONTROLS_RIGHT = "map_controls_right"
    }
}
