package jr.brian.thorhud.ui.map.custom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-game persistence of the atlas page's zoom + pan + follow state
 * so the user's view survives app restarts. One triple per game:
 *
 *   <game>_scale   : Float — camera zoom (image-space pixels per screen pixel).
 *   <game>_ox      : Float — pan offset X.
 *   <game>_oy      : Float — pan offset Y.
 *   <game>_follow  : Boolean — "follow the player" toggle; default true.
 *
 * Nothing here depends on the image or screen size being the same at
 * save vs restore time — the renderer re-wraps screen_width /
 * screen_height / image_width / image_height around the saved scale +
 * offset on first layout.
 */
@Singleton
class AtlasViewportPrefs @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Saved(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val follow: Boolean,
    )

    fun read(game: CustomMapStore.Game): Saved? {
        val k = keyPrefix(game)
        if (!prefs.contains(k + "scale")) return null
        return Saved(
            scale   = prefs.getFloat(k + "scale",   1f),
            offsetX = prefs.getFloat(k + "ox",      0f),
            offsetY = prefs.getFloat(k + "oy",      0f),
            follow  = prefs.getBoolean(k + "follow", true),
        )
    }

    fun writeViewport(game: CustomMapStore.Game,
                      scale: Float, offsetX: Float, offsetY: Float) {
        val k = keyPrefix(game)
        prefs.edit()
            .putFloat(k + "scale",   scale)
            .putFloat(k + "ox",      offsetX)
            .putFloat(k + "oy",      offsetY)
            .apply()
    }

    fun writeFollow(game: CustomMapStore.Game, follow: Boolean) {
        prefs.edit().putBoolean(keyPrefix(game) + "follow", follow).apply()
    }

    private fun keyPrefix(game: CustomMapStore.Game): String = when (game) {
        CustomMapStore.Game.IV -> "iv_"
        CustomMapStore.Game.V  -> "v_"
    }

    companion object {
        private const val FILE = "atlas_viewport_prefs"
    }
}
