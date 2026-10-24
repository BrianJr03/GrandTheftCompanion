package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationModelTest {

    @Test
    fun threePairsRecoverTheExactAffine() {
        // Known affine: px = 2*wx + 100,  py = -3*wy + 200
        val known = Calibration(
            a = 2.0f, b = 0.0f, c = 100.0f,
            d = 0.0f, e = -3.0f, f = 200.0f,
            maxResidualPx = 0.0f,
        )
        val worlds = listOf(
            WorldPoint(0.0f, 0.0f),
            WorldPoint(100.0f, 0.0f),
            WorldPoint(0.0f, 50.0f),
        )
        val pairs = worlds.map { w ->
            CalibrationPair(w, known.worldToPixel(w.x, w.y))
        }
        val result = CalibrationModel.solve(pairs)
        assertTrue("got $result", result is CalibrationResult.Ok)
        val got = (result as CalibrationResult.Ok).calibration
        assertEquals(known.a, got.a, 1e-3f)
        assertEquals(known.b, got.b, 1e-3f)
        assertEquals(known.c, got.c, 1e-3f)
        assertEquals(known.d, got.d, 1e-3f)
        assertEquals(known.e, got.e, 1e-3f)
        assertEquals(known.f, got.f, 1e-3f)
        assertEquals(0.0f, got.maxResidualPx, 1e-3f)
    }

    @Test
    fun exactFitHasZeroResidualWithMoreThanThreePairs() {
        // Same known transform, four pairs; the least-squares solve
        // should still land on it exactly because they are consistent.
        val known = Calibration(
            a = 1.5f, b = 0.5f, c = -30.0f,
            d = -0.2f, e = 2.0f, f = 10.0f,
            maxResidualPx = 0.0f,
        )
        val worlds = listOf(
            WorldPoint(0.0f, 0.0f),
            WorldPoint(100.0f, 10.0f),
            WorldPoint(50.0f, 200.0f),
            WorldPoint(-80.0f, 150.0f),
        )
        val pairs = worlds.map { w ->
            CalibrationPair(w, known.worldToPixel(w.x, w.y))
        }
        val got = (CalibrationModel.solve(pairs) as CalibrationResult.Ok).calibration
        assertEquals(0.0f, got.maxResidualPx, 1e-2f)
    }

    @Test
    fun oneBadPairProducesANonZeroResidual() {
        val known = Calibration(
            a = 2.0f, b = 0.0f, c = 100.0f,
            d = 0.0f, e = -3.0f, f = 200.0f,
            maxResidualPx = 0.0f,
        )
        val worlds = listOf(
            WorldPoint(0.0f, 0.0f),
            WorldPoint(100.0f, 0.0f),
            WorldPoint(0.0f, 50.0f),
            WorldPoint(100.0f, 50.0f),
        )
        val pairs = worlds.map { w ->
            CalibrationPair(w, known.worldToPixel(w.x, w.y))
        }.toMutableList()
        // Nudge the last tap by 10 px in each axis — that is a real
        // user mistake, not a reversed sign.
        val last = pairs.last()
        pairs[pairs.lastIndex] = CalibrationPair(
            last.world,
            PixelPoint(last.pixel.px + 10f, last.pixel.py + 10f),
        )
        val got = (CalibrationModel.solve(pairs) as CalibrationResult.Ok).calibration
        assertTrue("expected non-zero residual, got ${got.maxResidualPx}",
            got.maxResidualPx > 1.0f)
    }

    @Test
    fun fewerThanThreePairsIsRejected() {
        val r = CalibrationModel.solve(
            listOf(
                CalibrationPair(WorldPoint(0f, 0f), PixelPoint(0f, 0f)),
                CalibrationPair(WorldPoint(1f, 1f), PixelPoint(1f, 1f)),
            )
        )
        assertEquals(CalibrationResult.Reason.TOO_FEW_PAIRS,
            (r as CalibrationResult.Rejected).reason)
    }

    @Test
    fun collinearWorldPointsAreRejected() {
        // Three points along y = x, no spread perpendicular.
        val pairs = listOf(
            CalibrationPair(WorldPoint(0f, 0f),   PixelPoint(0f, 0f)),
            CalibrationPair(WorldPoint(10f, 10f), PixelPoint(10f, 10f)),
            CalibrationPair(WorldPoint(20f, 20f), PixelPoint(20f, 20f)),
        )
        val r = CalibrationModel.solve(pairs)
        assertEquals(CalibrationResult.Reason.DEGENERATE,
            (r as CalibrationResult.Rejected).reason)
    }

    @Test
    fun worldToPixelAndBackRoundTrips() {
        val cal = Calibration(
            a = 2.0f, b = 0.3f, c = 100.0f,
            d = -0.1f, e = -3.0f, f = 200.0f,
            maxResidualPx = 0.0f,
        )
        val w = WorldPoint(321.0f, -42.5f)
        val p = cal.worldToPixel(w.x, w.y)
        val back = cal.pixelToWorld(p.px, p.py)
        assertEquals(w.x, back.x, 1e-2f)
        assertEquals(w.y, back.y, 1e-2f)
    }

    @Test
    fun recoversAnAffineThatIncludesRotation() {
        // The two pause-map textures are axis-aligned so the fitted
        // b and d will be ~0 in practice, but the solver must still
        // handle a rotated map in case the stitch is slightly off.
        val theta = 0.1f
        val cos = kotlin.math.cos(theta)
        val sin = kotlin.math.sin(theta)
        val known = Calibration(
            a = cos, b = -sin, c = 50f,
            d = sin, e = cos, f = -30f,
            maxResidualPx = 0.0f,
        )
        val worlds = listOf(
            WorldPoint(0.0f, 0.0f),
            WorldPoint(100.0f, 0.0f),
            WorldPoint(0.0f, 100.0f),
            WorldPoint(100.0f, 100.0f),
        )
        val pairs = worlds.map { w -> CalibrationPair(w, known.worldToPixel(w.x, w.y)) }
        val got = (CalibrationModel.solve(pairs) as CalibrationResult.Ok).calibration
        assertEquals(known.a, got.a, 1e-3f)
        assertEquals(known.b, got.b, 1e-3f)
        assertEquals(0.0f, got.maxResidualPx, 1e-2f)
    }
}
