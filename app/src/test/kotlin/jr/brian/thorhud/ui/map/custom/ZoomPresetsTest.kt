package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPresetsTest {

    // A calibration whose Jacobian det is 1 so 1 image px = 1 metre.
    private val cal = Calibration(
        a = 1f, b = 0f, c = 0f,
        d = 0f, e = 1f, f = 0f,
        maxResidualPx = 0f,
    )

    @Test fun scalesForProducesFitFirstThenMPerPx() {
        val scales = ZoomPresets.scalesFor(fitScale = 0.5f, cal = cal)
        assertEquals(ZoomPresets.Levels.size, scales.size)
        assertEquals(0.5f, scales[0], 1e-4f)      // fit
        // Levels: null, 8, 4, 2, 1, 0.5 → scales: fit, 1/8, 1/4, 1/2, 1, 2
        assertEquals(1f / 8f, scales[1], 1e-4f)
        assertEquals(1f / 4f, scales[2], 1e-4f)
        assertEquals(1f / 2f, scales[3], 1e-4f)
        assertEquals(1f,      scales[4], 1e-4f)
        assertEquals(2f,      scales[5], 1e-4f)
    }

    @Test fun levelForRoundsTripScale() {
        val level = ZoomPresets.levelFor(scale = 0.5f, cal = cal)
        assertNotNull(level)
        assertEquals(2f, level!!, 1e-3f) // 1 m/img-px / 0.5 scale → 2 m/screen-px
    }

    @Test fun nearestIndexPicksClosestInLog() {
        // At scale 0.9 the nearest preset is 1 m/px (scale 1).
        val idx = ZoomPresets.nearestIndex(current = 0.9f, fitScale = 0.1f, cal = cal)
        assertEquals(4, idx) // Levels index 4 == "1 m/px"
    }

    @Test fun nextAndPrevStepThroughTheTable() {
        val fit = 0.1f
        var s = fit
        // Walk forward from fit all the way to the finest preset.
        val reached = mutableListOf(s)
        for (i in 0 until 10) {
            val n = ZoomPresets.next(s, fit, cal)
            if (n == s) break
            s = n; reached.add(s)
        }
        assertEquals(ZoomPresets.Levels.size, reached.size)
        // And prev from the finest end walks us back to fit.
        var back = s
        for (i in 0 until 10) {
            val p = ZoomPresets.prev(back, fit, cal)
            if (p == back) break
            back = p
        }
        assertEquals(fit, back, 1e-4f)
    }

    @Test fun labelReadsAsMetresPerPixel() {
        val labelMid = ZoomPresets.labelFor(scale = 0.5f, fitScale = 0.1f, cal = cal)
        // At scale 0.5 under this cal: 2 m/px.
        assertEquals("2 m/px", labelMid)
        val labelFit = ZoomPresets.labelFor(scale = 0.1f, fitScale = 0.1f, cal = cal)
        assertEquals("fit", labelFit)
    }

    @Test fun labelFallsBackToRatioWithoutCalibration() {
        val label = ZoomPresets.labelFor(scale = 0.5f, fitScale = 0.25f, cal = null)
        assertTrue(label.startsWith("×"))
    }
}
