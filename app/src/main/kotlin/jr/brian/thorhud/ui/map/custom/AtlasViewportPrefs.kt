package jr.brian.thorhud.ui.map.custom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-game persistence of the atlas page's zoom + pan + follow state
 * so the user's view survives app restarts.
 *
 * Keys per game (prefix `iv_` / `v_`):
 *   `_scale`        Float - camera zoom (image-pixels / screen-pixel).
 *   `_ox`, `_oy`    Float - pan offsets (image pixels).
 *   `_follow`       Boolean - follow default on open; default false.
 *   `_preset`       Int   - current zoom-preset index (0 = fit).
 *
 * Session-global:
 *   `smoothing_ms`  Int   - follow time constant (60/120/240).
 *   `snap_zoom`     Boolean - snap pinch end to the nearest preset.
 *
 * Nothing here depends on image or screen size being the same at save
 * vs restore time - the renderer re-wraps those around the saved
 * values on first layout.
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
        val presetIndex: Int,
    )

    fun read(game: CustomMapStore.Game): Saved? {
        val k = keyPrefix(game)
        if (!prefs.contains(k + "scale")) return defaultFor(game)
        return Saved(
            scale        = prefs.getFloat(k + "scale",   1f),
            offsetX      = prefs.getFloat(k + "ox",      0f),
            offsetY      = prefs.getFloat(k + "oy",      0f),
            follow       = prefs.getBoolean(k + "follow", false),
            presetIndex  = prefs.getInt(k + "preset", DEFAULT_PRESET_INDEX),
        )
    }

    private fun defaultFor(game: CustomMapStore.Game): Saved? = when (game) {
        CustomMapStore.Game.IV -> Saved(
            scale        = 0.91276604f,
            offsetX      = -76.26889f,
            offsetY      = -83.921234f,
            follow       = false,
            presetIndex  = DEFAULT_PRESET_INDEX,
        )
        CustomMapStore.Game.V  -> null
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

    fun readFollow(game: CustomMapStore.Game): Boolean =
        prefs.getBoolean(keyPrefix(game) + "follow", false)

    fun writePresetIndex(game: CustomMapStore.Game, idx: Int) {
        prefs.edit().putInt(keyPrefix(game) + "preset", idx).apply()
    }

    fun readPresetIndex(game: CustomMapStore.Game): Int =
        prefs.getInt(keyPrefix(game) + "preset", DEFAULT_PRESET_INDEX)

    fun writeSmoothingMs(tauMs: Int) {
        prefs.edit().putInt("smoothing_ms", tauMs).apply()
    }

    fun readSmoothingMs(): Int = prefs.getInt("smoothing_ms", 120)

    fun writeSnapZoomToPresets(b: Boolean) {
        prefs.edit().putBoolean("snap_zoom", b).apply()
    }

    fun readSnapZoomToPresets(): Boolean = prefs.getBoolean("snap_zoom", true)

    private fun keyPrefix(game: CustomMapStore.Game): String = when (game) {
        CustomMapStore.Game.IV -> "iv_"
        CustomMapStore.Game.V  -> "v_"
    }

    companion object {
        private const val FILE = "atlas_viewport_prefs"
        private const val DEFAULT_PRESET_INDEX = 0 // fit
    }
}
