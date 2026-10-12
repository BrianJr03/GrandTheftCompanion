package jr.brian.thorhud.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private companion object {
        const val FILE = "hud_prefs"
        const val KEY_PAGER_PAGE = "pager_page"
        const val KEY_MIRROR_SCALE = "mirror_scale"
        const val KEY_MIRROR_OFFSET_X = "mirror_offset_x"
        const val KEY_MIRROR_OFFSET_Y = "mirror_offset_y"
    }
}
