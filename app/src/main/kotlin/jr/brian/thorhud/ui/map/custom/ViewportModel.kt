package jr.brian.thorhud.ui.map.custom

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin viewport maths for the custom-map (atlas) page.
 *
 * The map image lives in "image pixel" space (0..imageWidth,
 * 0..imageHeight). The visible viewport in screen pixel space is a
 * window into the image. [scale] = 1.0 means one image pixel per
 * screen pixel; a user pinch-in multiplies the scale, pinch-out
 * divides it. [offsetX]/[offsetY] shift the image centre within the
 * screen, in image pixels.
 *
 * The transform is image → screen:
 *   screenX = (imagePx - cx + offsetX) * scale + screenW / 2
 *   screenY = (imagePy - cy + offsetY) * scale + screenH / 2
 * where (cx, cy) is the image's own centre. Clamping keeps the image
 * from being flung off screen entirely: at any scale the image's
 * centre may wander up to half the viewport across the image.
 *
 * The viewport also composes with a [Calibration] from CalibrationModel
 * so a world coordinate from the state frame can be drawn on screen in
 * one hop.
 */
data class ViewportModel(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    companion object {
        /** Minimum zoom; below this the map tiles are smaller than
         *  the gestures that produced them are usable. */
        const val MIN_SCALE: Float = 0.1f
        /** Maximum zoom; past this a pure overview bitmap no longer
         *  carries enough pixels and the renderer swaps in a region
         *  decode. [kRegionZoom] is the threshold. */
        const val MAX_SCALE: Float = 8.0f
        /** Scale at or above which a BitmapRegionDecoder read gives
         *  better detail than the downsampled overview. The custom
         *  map screen reads this from the viewport. */
        const val kRegionZoom: Float = 1.5f

        fun fit(
            imageWidth: Int,
            imageHeight: Int,
            screenWidth: Int,
            screenHeight: Int,
        ): ViewportModel {
            if (imageWidth <= 0 || imageHeight <= 0 ||
                screenWidth <= 0 || screenHeight <= 0) {
                return ViewportModel(1f, 0f, 0f, imageWidth, imageHeight,
                                     screenWidth, screenHeight)
            }
            val sx = screenWidth.toFloat() / imageWidth
            val sy = screenHeight.toFloat() / imageHeight
            val s = min(sx, sy).coerceIn(MIN_SCALE, MAX_SCALE)
            return ViewportModel(s, 0f, 0f, imageWidth, imageHeight,
                                 screenWidth, screenHeight)
        }
    }

    /** Convert image pixel coords to screen pixel coords. */
    fun imageToScreen(ix: Float, iy: Float): FloatArray {
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        val sx = (ix - cx + offsetX) * scale + screenWidth / 2f
        val sy = (iy - cy + offsetY) * scale + screenHeight / 2f
        return floatArrayOf(sx, sy)
    }

    /** Inverse of [imageToScreen]: a tap in screen pixels back to the
     *  image's own pixel space. */
    fun screenToImage(sx: Float, sy: Float): FloatArray {
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        val ix = (sx - screenWidth / 2f) / scale + cx - offsetX
        val iy = (sy - screenHeight / 2f) / scale + cy - offsetY
        return floatArrayOf(ix, iy)
    }

    /** World → screen through a calibration. */
    fun worldToScreen(cal: Calibration, wx: Float, wy: Float): FloatArray {
        val p = cal.worldToPixel(wx, wy)
        return imageToScreen(p.px, p.py)
    }

    /** Screen → world through a calibration. */
    fun screenToWorld(cal: Calibration, sx: Float, sy: Float): WorldPoint {
        val img = screenToImage(sx, sy)
        return cal.pixelToWorld(img[0], img[1])
    }

    /** Rebuild with pan/scale updated and offsets clamped so the image
     *  centre stays within half the viewport of the image bounds —
     *  past that the image would be entirely off screen. */
    fun transform(deltaOffsetX: Float, deltaOffsetY: Float, zoom: Float): ViewportModel {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        // The clamp is on the OFFSET itself: a positive offsetX shifts
        // the image centre right in screen space, so the image's left
        // edge is at (cx - offsetX) * scale from the screen centre.
        // "Image fully off the right side" happens when
        //   (-cx + offsetX) * scale > screenW / 2
        // i.e. offsetX > cx + screenW / (2 * scale). Flip for the left.
        val halfScreenImgX = screenWidth / (2f * newScale)
        val halfScreenImgY = screenHeight / (2f * newScale)
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        val minOffX = -(cx + halfScreenImgX)
        val maxOffX =  (cx + halfScreenImgX)
        val minOffY = -(cy + halfScreenImgY)
        val maxOffY =  (cy + halfScreenImgY)
        val newOffX = (offsetX + deltaOffsetX / newScale).coerceIn(minOffX, maxOffX)
        val newOffY = (offsetY + deltaOffsetY / newScale).coerceIn(minOffY, maxOffY)
        return copy(scale = newScale, offsetX = newOffX, offsetY = newOffY)
    }

    /** Pan the viewport so the given image point is at the screen
     *  centre. Used by "follow mode" to recenter on the player. */
    fun centerOnImage(ix: Float, iy: Float): ViewportModel {
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        return copy(offsetX = cx - ix, offsetY = cy - iy)
    }

    /** True when the current scale warrants swapping the overview for
     *  a region decode. */
    fun shouldUseRegionDecoder(): Boolean = scale >= kRegionZoom

    /** Returns the angle, in degrees clockwise from screen "up", at
     *  which to draw a triangle representing a ped with the given
     *  heading. The game's heading-degree convention differs between
     *  IV and V (and in both the sign can flip under rotation of the
     *  radar texture that we have no stable source for), so the
     *  caller applies an [offsetDeg] and an [invert] gate that are
     *  tuned per game by eye on-device — see atlas gates 2 and 4.
     *  Pure maths; the UI just passes this to `Modifier.rotate`. */
    fun headingToScreenDegrees(
        headingDegrees: Float,
        offsetDeg: Float = 0f,
        invert: Boolean = false,
    ): Float {
        val signed = if (invert) -headingDegrees else headingDegrees
        return signed + offsetDeg
    }
}
