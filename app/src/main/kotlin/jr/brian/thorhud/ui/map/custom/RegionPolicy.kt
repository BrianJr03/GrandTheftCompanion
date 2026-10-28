package jr.brian.thorhud.ui.map.custom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * When at the finest zooms the overview bitmap is too coarse, we swap
 * in a decoded region from a [android.graphics.BitmapRegionDecoder].
 * Decoding every frame hitches the Thor, so the policy here batches:
 *
 *  * Decode a rect sized **marginFactor** times the visible viewport,
 *    centred on the camera.
 *  * Keep using that region until the camera leaves the **inner**
 *    `innerFraction` of it, OR the viewport scale changes by more than
 *    `scaleDeltaFraction`.
 *
 * The maths runs in image-pixel space; the decoder consumes an
 * image-pixel rect directly. Pure Kotlin so it is unit-testable.
 */
object RegionPolicy {

    /** Expand the viewport by this factor on each side for the decoded
     *  rect, so small pans don't force a redecode. 1.5x is a 25% halo
     *  on each side. */
    const val kMarginFactor: Float = 1.5f

    /** Trigger a redecode when the camera leaves this fraction of the
     *  previous rect (inside-out). 0.60 means "camera must stay inside
     *  the inner 60% of the last rect; past that, redecode". */
    const val kInnerFraction: Float = 0.60f

    /** Trigger a redecode when the viewport scale changes by more than
     *  this fraction of the scale the last region was decoded for. */
    const val kScaleDeltaFraction: Float = 0.25f

    data class RegionRect(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val forScale: Float,
    ) {
        val width: Int  get() = right - left
        val height: Int get() = bottom - top
    }

    /** Build a decode rect centred on [centerImageX, centerImageY] with
     *  margin [kMarginFactor]. Clamped to the image bounds. */
    fun buildRect(
        centerImageX: Float, centerImageY: Float,
        scale: Float,
        screenWidth: Int, screenHeight: Int,
        imageWidth: Int, imageHeight: Int,
        marginFactor: Float = kMarginFactor,
    ): RegionRect {
        val vwImg = screenWidth  / scale
        val vhImg = screenHeight / scale
        val halfW = (vwImg * marginFactor) * 0.5f
        val halfH = (vhImg * marginFactor) * 0.5f
        val left   = (centerImageX - halfW).roundToInt().coerceAtLeast(0)
        val top    = (centerImageY - halfH).roundToInt().coerceAtLeast(0)
        val right  = (centerImageX + halfW).roundToInt().coerceAtMost(imageWidth)
        val bottom = (centerImageY + halfH).roundToInt().coerceAtMost(imageHeight)
        return RegionRect(left, top, right, bottom, scale)
    }

    /** Should a new region decode be scheduled?
     *
     * `null` current → always true.
     * Scale change > [kScaleDeltaFraction] → true.
     * Camera outside the inner [kInnerFraction] of the current rect →
     *   true.
     * Otherwise → false. */
    fun shouldRedecode(
        current: RegionRect?,
        centerImageX: Float, centerImageY: Float,
        scale: Float,
        innerFraction: Float = kInnerFraction,
        scaleDeltaFraction: Float = kScaleDeltaFraction,
    ): Boolean {
        if (current == null) return true
        val scaleDelta = abs(scale - current.forScale) / max(current.forScale, 1e-6f)
        if (scaleDelta > scaleDeltaFraction) return true
        val cx = (current.left + current.right) * 0.5f
        val cy = (current.top + current.bottom) * 0.5f
        val innerHalfW = current.width  * 0.5f * innerFraction
        val innerHalfH = current.height * 0.5f * innerFraction
        val dx = abs(centerImageX - cx)
        val dy = abs(centerImageY - cy)
        return dx > innerHalfW || dy > innerHalfH
    }
}
