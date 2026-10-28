package jr.brian.thorhud.ui.map.custom

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * When at the finest zooms the overview bitmap is too coarse, we swap
 * in a decoded region from a [android.graphics.BitmapRegionDecoder].
 * Decoding every frame hitches the Thor, so the policy here batches:
 *
 *  * Decode a square rect sized **marginFactor** times the viewport
 *    **diagonal**, centered on the camera. A diagonal-sized square
 *    stays fully populated under any rotation of the inscribed
 *    viewport, so the rotating minimap never shows a seam from the
 *    region bitmap's edge.
 *  * Keep using that region until the camera leaves the **inner**
 *    `innerFraction` of it, OR the viewport scale changes by more
 *    than `scaleDeltaFraction`, OR the rotation changes by more than
 *    `rotationDeltaDeg` from the rotation the rect was decoded for.
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

    /** Trigger a redecode when the viewport rotation changes by more
     *  than this many degrees from the rotation the current rect was
     *  decoded for. 20 degrees is a visible-but-not-redecoding-every-
     *  frame compromise; smaller means more decodes under a turn. */
    const val kRotationDeltaDeg: Float = 20f

    data class RegionRect(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val forScale: Float,
        val forRotationDeg: Float = 0f,
    ) {
        val width: Int  get() = right - left
        val height: Int get() = bottom - top
    }

    /** Build a decode rect centered on [centerImageX, centerImageY] as
     *  a square sized to [marginFactor] times the viewport diagonal
     *  (so a rotated viewport inscribed in it is fully covered at any
     *  angle). Clamped to the image bounds; a camera near a corner
     *  gets an asymmetric rect. */
    fun buildRect(
        centerImageX: Float, centerImageY: Float,
        scale: Float,
        screenWidth: Int, screenHeight: Int,
        imageWidth: Int, imageHeight: Int,
        rotationDeg: Float = 0f,
        marginFactor: Float = kMarginFactor,
    ): RegionRect {
        // Side of a square that covers any rotation of the viewport
        // inscribed in it: viewport diagonal in image-pixel space.
        val sideImg = hypot(screenWidth.toFloat(), screenHeight.toFloat()) / scale
        val half = sideImg * marginFactor * 0.5f
        val left   = (centerImageX - half).roundToInt().coerceAtLeast(0)
        val top    = (centerImageY - half).roundToInt().coerceAtLeast(0)
        val right  = (centerImageX + half).roundToInt().coerceAtMost(imageWidth)
        val bottom = (centerImageY + half).roundToInt().coerceAtMost(imageHeight)
        return RegionRect(left, top, right, bottom, scale, rotationDeg)
    }

    /** Should a new region decode be scheduled?
     *
     * `null` current → always true.
     * Scale change > [kScaleDeltaFraction] → true.
     * Rotation change > [kRotationDeltaDeg] → true.
     * Camera outside the inner [kInnerFraction] of the current rect →
     *   true.
     * Otherwise → false. */
    fun shouldRedecode(
        current: RegionRect?,
        centerImageX: Float, centerImageY: Float,
        scale: Float,
        rotationDeg: Float = 0f,
        innerFraction: Float = kInnerFraction,
        scaleDeltaFraction: Float = kScaleDeltaFraction,
        rotationDeltaDeg: Float = kRotationDeltaDeg,
    ): Boolean {
        if (current == null) return true
        val scaleDelta = abs(scale - current.forScale) / max(current.forScale, 1e-6f)
        if (scaleDelta > scaleDeltaFraction) return true
        val rotDelta = abs(PositionTrack.shortestArcDeg(current.forRotationDeg, rotationDeg))
        if (rotDelta > rotationDeltaDeg) return true
        val cx = (current.left + current.right) * 0.5f
        val cy = (current.top + current.bottom) * 0.5f
        val innerHalfW = current.width  * 0.5f * innerFraction
        val innerHalfH = current.height * 0.5f * innerFraction
        val dx = abs(centerImageX - cx)
        val dy = abs(centerImageY - cy)
        return dx > innerHalfW || dy > innerHalfH
    }
}
