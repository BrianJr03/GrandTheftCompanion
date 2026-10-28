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

    @Test fun buildRectUsesDiagonalSoRotationHasHeadroom() {
        val r = RegionPolicy.buildRect(
            centerImageX = 1000f, centerImageY = 1000f,
            scale = 1f,
            screenWidth = 500, screenHeight = 400,
            imageWidth = 4000, imageHeight = 4000,
            marginFactor = 1.5f,
        )
        // sideImg = sqrt(500^2 + 400^2) = 640.31, * 1.5 = 960.47.
        // Half ~480. Rect centred at (1000,1000): [520, 1480] x [520, 1480].
        assertEquals(520, r.left)
        assertEquals(520, r.top)
        assertEquals(1480, r.right)
        assertEquals(1480, r.bottom)
    }

    @Test fun rotationBeyondThresholdTriggers() {
        val rect = RegionPolicy.RegionRect(0, 0, 1000, 800,
            forScale = 1f, forRotationDeg = 0f)
        assertFalse(RegionPolicy.shouldRedecode(rect, 500f, 400f, 1f, rotationDeg = 15f))
        assertTrue(RegionPolicy.shouldRedecode(rect, 500f, 400f, 1f, rotationDeg = 25f))
    }

    @Test fun rotationShortArcCountsForTrigger() {
        // Rect decoded at 350 deg; now at 10 deg: short arc = 20 deg
        // which is just over the 20 deg threshold boundary. Pick a
        // large-enough step to make the test unambiguous.
        val rect = RegionPolicy.RegionRect(0, 0, 1000, 800,
            forScale = 1f, forRotationDeg = 350f)
        assertFalse(RegionPolicy.shouldRedecode(rect, 500f, 400f, 1f, rotationDeg = 5f))
        assertTrue(RegionPolicy.shouldRedecode(rect, 500f, 400f, 1f, rotationDeg = 25f))
    }
}
