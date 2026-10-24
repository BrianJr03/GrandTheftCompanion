package jr.brian.thorhud.ui.map.custom

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pure Kotlin calibration for the custom-map (atlas) page.
 *
 * The user supplies 3+ pairs (worldX, worldY) ↔ (pixelX, pixelY) by
 * tapping where known-world-position blips sit on the ripped map
 * image, or by using "use my position" at a tap. We fit a 2×3 affine
 *
 *     px = a*wx + b*wy + c
 *     py = d*wx + e*wy + f
 *
 * The two linear halves are independent and solved by normal equations
 * (A^T A x = A^T b) in a plain 3×3 matrix invert. Three well-separated
 * pairs give an exact solution; more pairs become a least-squares fit.
 *
 * The game's two pause-map textures are axis-aligned and north-up, so
 * the fitted b and d end up small; letting the fit absorb a tiny
 * rotation / shear is kinder than hard-coding "no rotation" and seeing
 * residuals blow up on a slightly off tap.
 *
 * Nothing here touches an Android class, so it is unit-testable from
 * the host JVM.
 */

data class WorldPoint(val x: Float, val y: Float)
data class PixelPoint(val px: Float, val py: Float)
data class CalibrationPair(val world: WorldPoint, val pixel: PixelPoint)

/** The result of a solve: the six affine coefficients + the largest
 *  per-pair pixel residual (the Euclidean distance between a pair's
 *  measured pixel and the pixel the fit predicts). A small residual
 *  on well-spread pairs is the signal that the user's taps are
 *  consistent; a large one is the signal to redo it.
 *
 *  Serialized next to the map image under files/map/{iv,v}.json so
 *  a calibration survives app restarts. */
@Serializable
data class Calibration(
    @SerialName("a") val a: Float,
    @SerialName("b") val b: Float,
    @SerialName("c") val c: Float,
    @SerialName("d") val d: Float,
    @SerialName("e") val e: Float,
    @SerialName("f") val f: Float,
    @SerialName("residual") val maxResidualPx: Float,
) {
    fun worldToPixel(x: Float, y: Float): PixelPoint =
        PixelPoint(a * x + b * y + c, d * x + e * y + f)

    /** Inverse of the affine. The 2×2 part has determinant [a*e - b*d];
     *  the game maps are nowhere near degenerate so this is safe for
     *  any accepted calibration. */
    fun pixelToWorld(px: Float, py: Float): WorldPoint {
        val det = a * e - b * d
        val dx = px - c
        val dy = py - f
        val wx = (e * dx - b * dy) / det
        val wy = (-d * dx + a * dy) / det
        return WorldPoint(wx, wy)
    }
}

sealed class CalibrationResult {
    data class Ok(val calibration: Calibration) : CalibrationResult()
    /** Not enough well-spread pairs. [reason] is a short machine-readable
     *  tag; the UI picks the string. */
    data class Rejected(val reason: Reason) : CalibrationResult()
    enum class Reason { TOO_FEW_PAIRS, DEGENERATE }
}

object CalibrationModel {

    /** Minimum pairs any solve needs. Three gives an exact fit when
     *  the points are not collinear; the UI will not accept fewer. */
    const val MIN_PAIRS: Int = 3

    /**
     * The world-space determinant below which the three (or more)
     * world points are considered too close to collinear to be
     * calibration-worthy. Picked empirically: real taps give
     * determinants in the millions on the GTA V map (coordinates in
     * the thousands), the floor exists to catch a user who taps three
     * points in a line. Scale-free check (/ bbox^2) would be stricter
     * but makes the maths opaque for little gain here.
     */
    private const val DEGENERATE_EPSILON = 1e-6f

    fun solve(pairs: List<CalibrationPair>): CalibrationResult {
        if (pairs.size < MIN_PAIRS) {
            return CalibrationResult.Rejected(CalibrationResult.Reason.TOO_FEW_PAIRS)
        }

        // Normal equations: A^T A x = A^T b, where each row of A is
        // [wx wy 1]. A^T A is a 3×3 symmetric positive-definite matrix
        // iff the world points are not collinear; the determinant
        // check below rejects the collinear case so the invert is
        // safe. Using doubles for the accumulate so a map in GTA V's
        // ~8000 x ~8000 world does not lose precision.
        var sxx = 0.0; var sxy = 0.0; var sx = 0.0
        var syy = 0.0; var sy = 0.0; var s1 = 0.0
        var spx = 0.0; var sypx = 0.0; var sxpx = 0.0
        var spy = 0.0; var sypy = 0.0; var sxpy = 0.0
        for ((w, p) in pairs) {
            val wx = w.x.toDouble(); val wy = w.y.toDouble()
            val px = p.px.toDouble(); val py = p.py.toDouble()
            sxx += wx * wx
            sxy += wx * wy
            syy += wy * wy
            sx  += wx
            sy  += wy
            s1  += 1.0
            sxpx += wx * px
            sypx += wy * px
            spx  += px
            sxpy += wx * py
            sypy += wy * py
            spy  += py
        }

        // A^T A as a 3×3. Inverted analytically; readable and
        // dependency-free. Rows: [sxx sxy sx; sxy syy sy; sx sy s1].
        val m11 = sxx; val m12 = sxy; val m13 = sx
        val m22 = syy; val m23 = sy
        val m33 = s1
        val det = m11 * (m22 * m33 - m23 * m23) -
                  m12 * (m12 * m33 - m23 * m13) +
                  m13 * (m12 * m23 - m22 * m13)
        if (abs(det) < DEGENERATE_EPSILON) {
            return CalibrationResult.Rejected(CalibrationResult.Reason.DEGENERATE)
        }
        val invDet = 1.0 / det
        // Cofactor matrix for a symmetric 3×3.
        val c11 =  (m22 * m33 - m23 * m23) * invDet
        val c12 = -(m12 * m33 - m23 * m13) * invDet
        val c13 =  (m12 * m23 - m22 * m13) * invDet
        val c22 =  (m11 * m33 - m13 * m13) * invDet
        val c23 = -(m11 * m23 - m12 * m13) * invDet
        val c33 =  (m11 * m22 - m12 * m12) * invDet

        fun solveHalf(sx_p: Double, sy_p: Double, s_p: Double): Triple<Double, Double, Double> {
            val a = c11 * sx_p + c12 * sy_p + c13 * s_p
            val b = c12 * sx_p + c22 * sy_p + c23 * s_p
            val c = c13 * sx_p + c23 * sy_p + c33 * s_p
            return Triple(a, b, c)
        }
        val (a, b, c) = solveHalf(sxpx, sypx, spx)
        val (d, e, f) = solveHalf(sxpy, sypy, spy)

        // Residual: largest Euclidean pixel distance across all pairs.
        var worst = 0.0f
        for ((w, p) in pairs) {
            val predPx = a * w.x + b * w.y + c
            val predPy = d * w.x + e * w.y + f
            val dx = (predPx - p.px).toFloat()
            val dy = (predPy - p.py).toFloat()
            worst = max(worst, hypot(dx, dy))
        }

        return CalibrationResult.Ok(
            Calibration(
                a = a.toFloat(), b = b.toFloat(), c = c.toFloat(),
                d = d.toFloat(), e = e.toFloat(), f = f.toFloat(),
                maxResidualPx = worst,
            )
        )
    }
}
