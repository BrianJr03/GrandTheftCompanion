package jr.brian.thorhud.ui.map.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Discovery and I/O for the user's own ripped map images.
 *
 * The project ships no map imagery; the user drops one PNG per game
 * into the app's external files dir, which adb can push to without
 * root. The expected paths are:
 *
 *     /Android/data/<pkg>/files/map/v.png     (GTA V)
 *     /Android/data/<pkg>/files/map/iv.png    (GTA IV)
 *     /Android/data/<pkg>/files/map/v.json    (saved calibration, GTA V)
 *     /Android/data/<pkg>/files/map/iv.json   (saved calibration, GTA IV)
 *
 * [atlasPathFor] returns the exact absolute path for the settings
 * screen to show; the user needs to see it to tell a typo from a bug.
 *
 * All disk I/O is synchronous and fast — a PNG header peek plus a
 * ~200-byte JSON write — so no coroutine dispatcher is involved.
 * Image decode is a different story; see [decodeOverview] /
 * [openRegionDecoder].
 */
@Singleton
class CustomMapStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    private val _presence = MutableStateFlow(scanPresence())
    val presence: StateFlow<AtlasPresence> = _presence.asStateFlow()

    /** Game identifier the atlas uses; mirrors [GameKey] in the HUD
     *  prefs but kept local so this file has no cross-dependency. */
    enum class Game { IV, V }

    data class AtlasPresence(val hasIv: Boolean, val hasV: Boolean) {
        fun has(game: Game): Boolean = when (game) {
            Game.IV -> hasIv
            Game.V  -> hasV
        }
    }

    fun atlasPathFor(game: Game): String =
        imageFile(game).absolutePath

    fun hasImage(game: Game): Boolean = imageFile(game).exists()

    /** Refresh [presence] from disk; call after the user pushes a
     *  file in or the activity resumes. */
    fun refresh() {
        _presence.value = scanPresence()
    }

    fun readCalibration(game: Game): Calibration? =
        CalibrationIo.read(jsonFile(game))

    fun writeCalibration(game: Game, cal: Calibration): Boolean =
        CalibrationIo.write(jsonFile(game), cal)

    fun deleteCalibration(game: Game) {
        jsonFile(game).delete()
    }

    /**
     * Decode a downsampled overview bitmap for one game.
     *
     * On the Thor's bottom panel at 540×468 dp, a 4096-px long side is
     * plenty for pinch-out while leaving headroom to double for the
     * first zoom-in before region decoding takes over. Reads the PNG
     * header first (just dimensions) so an 8192×8192 image does not
     * decode into memory at full size and OOM.
     */
    fun decodeOverview(game: Game, maxLongSidePx: Int = kOverviewLongSide): Bitmap? {
        val f = imageFile(game)
        if (!f.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val longSide = max(bounds.outWidth, bounds.outHeight)
            val target = min(longSide, maxLongSidePx)
            // inSampleSize must be a power of 2; biggest value that
            // still leaves longSide / sample >= target.
            var sample = 1
            while (longSide / (sample * 2) >= target) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        }.onFailure {
            Log.w(TAG, "decode overview failed for ${f.absolutePath}: ${it.message}")
        }.getOrNull()
    }

    /** Open a region decoder for high-zoom slices. Returns null when
     *  the image is missing or unreadable. */
    @Suppress("DEPRECATION")
    fun openRegionDecoder(game: Game): BitmapRegionDecoder? {
        val f = imageFile(game)
        if (!f.exists()) return null
        return runCatching {
            // newInstance(path, isShareable) is the long-stable form;
            // the Context+Uri overload is API 31+ and offers nothing
            // extra here.
            BitmapRegionDecoder.newInstance(f.absolutePath, false)
        }.onFailure {
            Log.w(TAG, "region decoder failed: ${it.message}")
        }.getOrNull()
    }

    /** Decode one region (image pixel rect) from a region decoder,
     *  honouring an inSampleSize that fits the target screen size. */
    fun decodeRegion(
        decoder: BitmapRegionDecoder,
        rect: Rect,
        targetLongSidePx: Int,
    ): Bitmap? = runCatching {
        if (rect.isEmpty) return@runCatching null
        val longSide = max(rect.width(), rect.height())
        var sample = 1
        while (longSide / (sample * 2) >= targetLongSidePx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        decoder.decodeRegion(rect, opts)
    }.onFailure {
        Log.w(TAG, "decodeRegion failed: ${it.message}")
    }.getOrNull()

    private fun scanPresence(): AtlasPresence =
        AtlasPresence(hasIv = imageFile(Game.IV).exists(),
                      hasV  = imageFile(Game.V).exists())

    private fun mapDir(): File {
        val root = appContext.getExternalFilesDir(null)
            ?: appContext.filesDir
        return File(root, "map")
    }

    private fun imageFile(game: Game): File = File(mapDir(), fileName(game, "png"))
    private fun jsonFile(game: Game):  File = File(mapDir(), fileName(game, "json"))

    private fun fileName(game: Game, ext: String): String = when (game) {
        Game.IV -> "iv.$ext"
        Game.V  -> "v.$ext"
    }

    companion object {
        private const val TAG = "CustomMapStore"
        /** Default longest-side size for the overview bitmap. Pre-
         *  decoded at app start; the renderer swaps in a region
         *  decoder once the viewport scale crosses
         *  [ViewportModel.kRegionZoom]. */
        const val kOverviewLongSide: Int = 4096
    }
}
