package jr.brian.thorhud.ui.map.custom

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Zoom presets expressed in **metres per screen pixel**, so a given
 * preset means the same real-world framing on any atlas image. The
 * conversion from "metres per screen pixel" to a [ViewportModel.scale]
 * goes through the [Calibration] (which encodes "image pixels per
 * metre") and the user's zoom (which encodes "screen pixels per
 * image pixel").
 *
 *   screenPxPerMetre  = imagePxPerMetre * scale
 *   metresPerScreenPx = metresPerImagePx / scale
 *
 * The preset list runs coarse to fine. [Fit] is a special entry that
 * means "the fit scale for the current image + viewport", computed
 * at use-time rather than stored.
 */
object ZoomPresets {

    /** `null` means "fit"; numbers are metres per screen pixel. */
    val Levels: List<Float?> = listOf(null, 8f, 4f, 2f, 1f, 0.5f)

    /** Cap at 1.5x the image's native pixel ratio; beyond that it's
     *  just blur. The caller passes the native ratio in; we floor the
     *  resulting scale so this returns `scale`, not a preset index. */
    const val NativeZoomCap: Float = 1.5f

    /** Convert a metres-per-screen-pixel target into a
     *  [ViewportModel.scale] under the given calibration. Returns null
     *  if the calibration is degenerate (det ~ 0). */
    fun scaleFor(mPerPx: Float, cal: Calibration): Float? {
        val mPerImagePx = metresPerImagePixel(cal) ?: return null
        if (mPerPx <= 0f) return null
        return mPerImagePx / mPerPx
    }

    /** Reverse of [scaleFor]: given the current [ViewportModel.scale],
     *  return the metres-per-screen-pixel level it corresponds to.
     *  Returns null for degenerate calibrations. */
    fun levelFor(scale: Float, cal: Calibration): Float? {
        val mPerImagePx = metresPerImagePixel(cal) ?: return null
        if (scale <= 0f) return null
        return mPerImagePx / scale
    }

    /** Image pixels per metre is sqrt(|a*e - b*d|). */
    private fun metresPerImagePixel(cal: Calibration): Float? {
        val det = abs(cal.a * cal.e - cal.b * cal.d)
        if (det < 1e-12f) return null
        return (1.0 / sqrt(det.toDouble())).toFloat()
    }

    /** The scale index closest to [current], where index 0 is "fit".
     *  Fit is treated as the coarsest level for distance purposes. */
    fun nearestIndex(current: Float, fitScale: Float, cal: Calibration): Int {
        val scales = scalesFor(fitScale, cal)
        var bestIdx = 0
        var bestDelta = Float.POSITIVE_INFINITY
        for (i in scales.indices) {
            val d = abs(ln(scales[i]) - ln(current))
            if (d < bestDelta) { bestDelta = d; bestIdx = i }
        }
        return bestIdx
    }

    /** The scale one step coarser (zoom out) than [current]. If
     *  [current] is already at or below the coarsest preset, returns
     *  fit. */
    fun prev(current: Float, fitScale: Float, cal: Calibration): Float {
        val scales = scalesFor(fitScale, cal)
        val idx = nearestIndex(current, fitScale, cal)
        // "prev" = zoom out = smaller scale = lower index.
        val next = (idx - 1).coerceAtLeast(0)
        return scales[next]
    }

    /** The scale one step finer (zoom in) than [current]. Clamped at
     *  the finest preset. */
    fun next(current: Float, fitScale: Float, cal: Calibration): Float {
        val scales = scalesFor(fitScale, cal)
        val idx = nearestIndex(current, fitScale, cal)
        val next = (idx + 1).coerceAtMost(scales.lastIndex)
        return scales[next]
    }

    /** Expand [Levels] into concrete [ViewportModel.scale] values for
     *  the given calibration. Falls back to a single-entry [fitScale]
     *  list if the calibration cannot be read (degenerate det), so
     *  the overlay stays usable. */
    fun scalesFor(fitScale: Float, cal: Calibration): FloatArray {
        val mPerImagePx = metresPerImagePixel(cal)
        if (mPerImagePx == null) return floatArrayOf(fitScale)
        val out = FloatArray(Levels.size)
        for (i in Levels.indices) {
            val lvl = Levels[i]
            out[i] = if (lvl == null) fitScale else mPerImagePx / lvl
        }
        return out
    }

    /** Short display label for the overlay centre. Uses the metres-
     *  per-pixel number when it exists (e.g. "2 m/px"), falls back to
     *  "fit" for the fit entry, and a plain `×N` multiplier when
     *  there is no calibration yet. */
    fun labelFor(scale: Float, fitScale: Float, cal: Calibration?): String {
        if (cal == null) {
            val ratio = if (fitScale > 0f) scale / fitScale else 1f
            return "×%.1f".format(ratio)
        }
        val level = levelFor(scale, cal) ?: return "×?"
        if (abs(scale - fitScale) < fitScale * 0.02f) return "fit"
        return when {
            level >= 1f    -> "${level.toInt()} m/px"
            else            -> "%.1f m/px".format(level)
        }
    }

    private fun ln(x: Float): Float = kotlin.math.ln(x.toDouble()).toFloat()
}
