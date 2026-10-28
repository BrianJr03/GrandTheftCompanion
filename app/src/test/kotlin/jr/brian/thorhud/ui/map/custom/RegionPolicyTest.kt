package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPolicyTest {

    @Test fun firstDecodeAlwaysNeeded() {
        assertTrue(RegionPolicy.shouldRedecode(
            current = null,
            centerImageX = 100f, centerImageY = 100f, scale = 1f,
        ))
    }

    @Test fun scaleChangeAboveThresholdTriggers() {
        val rect = RegionPolicy.RegionRect(0, 0, 1000, 800, forScale = 1.0f)
        // 30% change in scale > 25% threshold.
        assertTrue(RegionPolicy.shouldRedecode(rect, 500f, 400f, scale = 1.3f))
        // 10% change does not trigger.
        assertFalse(RegionPolicy.shouldRedecode(rect, 500f, 400f, scale = 1.1f))
    }

    @Test fun stayingInsideInnerDoesNotTrigger() {
        // Rect is 1000x800 centred on (500, 400). Inner 60% is a
        // 600x480 box centred there; staying within half of that
        // (300 x, 240 y) does not redecode.
        val rect = RegionPolicy.RegionRect(0, 0, 1000, 800, forScale = 1f)
        assertFalse(RegionPolicy.shouldRedecode(rect, 500f + 299f, 400f, 1f))
        assertFalse(RegionPolicy.shouldRedecode(rect, 500f, 400f - 239f, 1f))
    }

    @Test fun leavingInnerBoxTriggers() {
        val rect = RegionPolicy.RegionRect(0, 0, 1000, 800, forScale = 1f)
        assertTrue(RegionPolicy.shouldRedecode(rect, 500f + 301f, 400f, 1f))
    }

    @Test fun buildRectCentresAndClampsToImageBounds() {
        val r = RegionPolicy.buildRect(
            centerImageX = 100f, centerImageY = 100f,
            scale = 1f,
            screenWidth = 500, screenHeight = 400,
            imageWidth = 2000, imageHeight = 2000,
            marginFactor = 1.5f,
        )
        // margin: 500 * 1.5 = 750 wide, 400 * 1.5 = 600 tall.
        // half: 375 x, 300 y, so the raw rect goes to -275..475 x
        // and -200..400 y. Clamped at zero on top-left.
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(475, r.right)
        assertEquals(400, r.bottom)
    }
}
