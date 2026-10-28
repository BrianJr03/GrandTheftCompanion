package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportModelTest {

    private val vp = ViewportModel(
        scale = 1.0f,
        offsetX = 0f,
        offsetY = 0f,
        imageWidth = 1000,
        imageHeight = 800,
        screenWidth = 500,
        screenHeight = 400,
    )

    @Test
    fun imageCenterLandsAtScreenCenterWithZeroOffset() {
        val s = vp.imageToScreen(500f, 400f)
        assertEquals(250f, s[0], 1e-3f)
        assertEquals(200f, s[1], 1e-3f)
    }

    @Test
    fun imageAndScreenRoundTrip() {
        val panned = vp.copy(scale = 2.0f, offsetX = -100f, offsetY = 50f)
        val img = floatArrayOf(321f, 42f)
        val screen = panned.imageToScreen(img[0], img[1])
        val back = panned.screenToImage(screen[0], screen[1])
        assertEquals(img[0], back[0], 1e-3f)
        assertEquals(img[1], back[1], 1e-3f)
    }

    @Test
    fun fitLandsOnMinScalingAxis() {
        val f = ViewportModel.fit(
            imageWidth = 1000, imageHeight = 500,
            screenWidth = 500, screenHeight = 500,
        )
        // The 1000-wide image fits into 500 at scale 0.5; the 500-tall
        // image fits at scale 1.0; min is 0.5.
        assertEquals(0.5f, f.scale, 1e-3f)
    }

    @Test
    fun transformScalesAndClampsOffset() {
        val zoomed = vp.transform(deltaOffsetX = 0f, deltaOffsetY = 0f, zoom = 2.0f)
        assertEquals(2.0f, zoomed.scale, 1e-3f)
        // At scale 2.0, panning the viewport by half a screen width in
        // image pixels is screenW / (2 * scale) = 125.
        val panned = zoomed.transform(deltaOffsetX = 125f * 2.0f, deltaOffsetY = 0f, zoom = 1f)
        assertEquals(125f, panned.offsetX, 1e-3f)
    }

    @Test
    fun clampStopsImageBeingFullyOffScreen() {
        // Pan a long way: the clamp caps the offset so some of the
        // image stays on screen.
        val panned = vp.transform(deltaOffsetX = 100000f, deltaOffsetY = 0f, zoom = 1f)
        val halfScreenImg = vp.screenWidth / (2f * 1f)
        val cx = vp.imageWidth / 2f
        assertTrue(panned.offsetX <= cx + halfScreenImg + 1e-3f)
    }

    @Test
    fun worldToScreenGoesThroughTheCalibration() {
        val cal = Calibration(
            a = 2f, b = 0f, c = 100f,
            d = 0f, e = -3f, f = 200f,
            maxResidualPx = 0f,
        )
        val panned = vp.copy(scale = 1.0f, offsetX = 0f, offsetY = 0f)
        // World (10, 20) maps to image (120, 140). Image centre of this
        // 1000x800 is (500, 400); scale 1 gives screen (120-500+250,
        // 140-400+200) = (-130, -60).
        val s = panned.worldToScreen(cal, 10f, 20f)
        assertEquals(-130f, s[0], 1e-2f)
        assertEquals(-60f, s[1], 1e-2f)
    }

    @Test
    fun screenToWorldInvertsWorldToScreen() {
        val cal = Calibration(
            a = 1.5f, b = 0.2f, c = -20f,
            d = -0.1f, e = -1.4f, f = 50f,
            maxResidualPx = 0f,
        )
        val panned = vp.copy(scale = 0.75f, offsetX = 42f, offsetY = -13f)
        val s = panned.worldToScreen(cal, 234.5f, -98.0f)
        val back = panned.screenToWorld(cal, s[0], s[1])
        assertEquals(234.5f, back.x, 1e-2f)
        assertEquals(-98.0f, back.y, 1e-2f)
    }

    @Test
    fun centerOnImageAlignsAPointWithScreenCenter() {
        val centered = vp.centerOnImage(234f, 123f)
        val s = centered.imageToScreen(234f, 123f)
        assertEquals(vp.screenWidth / 2f, s[0], 1e-3f)
        assertEquals(vp.screenHeight / 2f, s[1], 1e-3f)
    }

    @Test
    fun shouldUseRegionDecoderTripsPastThreshold() {
        assertFalse(vp.shouldUseRegionDecoder())
        assertTrue(vp.copy(scale = 2.0f).shouldUseRegionDecoder())
    }

    @Test fun rotationRoundTripAtManyAngles() {
        val base = vp.copy(scale = 2.0f, offsetX = 37f, offsetY = -42f)
        val img = floatArrayOf(321f, 150f)
        for (rot in listOf(0f, 37f, 90f, 180f, 270f, 359f, 0.0001f)) {
            val rotated = base.copy(rotationDeg = rot)
            val screen = rotated.imageToScreen(img[0], img[1])
            val back = rotated.screenToImage(screen[0], screen[1])
            assertEquals("x at $rot deg", img[0], back[0], 1e-2f)
            assertEquals("y at $rot deg", img[1], back[1], 1e-2f)
        }
    }

    @Test fun rotation90SendsEastPointBelowCenter() {
        // Compose rotate(deg) is clockwise visually in y-down. A point
        // one px east of the image center at scale 1 / zero offsets,
        // rotated clockwise 90, lands one px below screen center.
        val r90 = vp.copy(rotationDeg = 90f)
        val east = floatArrayOf(vp.imageWidth / 2f + 1f, vp.imageHeight / 2f)
        val s = r90.imageToScreen(east[0], east[1])
        assertEquals(vp.screenWidth / 2f, s[0], 1e-2f)
        assertEquals(vp.screenHeight / 2f + 1f, s[1], 1e-2f)
    }

    @Test fun worldToScreenGoesThroughRotation() {
        val cal = Calibration(
            a = 1f, b = 0f, c = 0f,
            d = 0f, e = 1f, f = 0f,
            maxResidualPx = 0f,
        )
        val cx = vp.imageWidth / 2f
        val cy = vp.imageHeight / 2f
        val s = vp.copy(rotationDeg = 90f).worldToScreen(cal, cx + 1f, cy)
        assertEquals(vp.screenWidth / 2f, s[0], 1e-2f)
        assertEquals(vp.screenHeight / 2f + 1f, s[1], 1e-2f)
    }

    @Test fun screenToWorldInvertsAtNonTrivialAngle() {
        val cal = Calibration(
            a = 1.5f, b = 0.2f, c = -20f,
            d = -0.1f, e = -1.4f, f = 50f,
            maxResidualPx = 0f,
        )
        val panned = vp.copy(scale = 0.75f, offsetX = 42f, offsetY = -13f, rotationDeg = 127f)
        val s = panned.worldToScreen(cal, 234.5f, -98.0f)
        val back = panned.screenToWorld(cal, s[0], s[1])
        assertEquals(234.5f, back.x, 2e-2f)
        assertEquals(-98.0f, back.y, 2e-2f)
    }
}
