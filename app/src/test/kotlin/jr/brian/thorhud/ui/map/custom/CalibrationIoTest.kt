package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Unit tests for [CalibrationIo]. Covers the round-trip, the empty
 * / missing / corrupt cases, and generates a 64×64 synthetic
 * checkerboard PNG so the test fixtures are real byte sequences and
 * never derived from a shipped map texture.
 */
class CalibrationIoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsThroughJson() {
        val cal = Calibration(
            a = 1.5f, b = -0.1f, c = 100f,
            d = 0.1f, e = -2.0f, f = 50f,
            maxResidualPx = 2.3f,
        )
        val f = File(tmp.newFolder("map"), "v.json")
        assertTrue(CalibrationIo.write(f, cal))
        val back = CalibrationIo.read(f)
        assertNotNull(back)
        assertEquals(cal, back)
    }

    @Test
    fun missingFileReadsAsNull() {
        val f = File(tmp.newFolder("map"), "iv.json")
        assertNull(CalibrationIo.read(f))
    }

    @Test
    fun corruptJsonReadsAsNull() {
        val f = File(tmp.newFolder("map"), "iv.json")
        f.writeText("this is not json at all")
        assertNull(CalibrationIo.read(f))
    }

    @Test
    fun writeCreatesParentDirectory() {
        val root = tmp.newFolder("fresh")
        val f = File(File(root, "map"), "v.json")
        val cal = Calibration(1f, 0f, 0f, 0f, 1f, 0f, 0f)
        assertTrue(CalibrationIo.write(f, cal))
        assertTrue(f.exists())
    }

    @Test
    fun syntheticCheckerboardPngDecodesToExpectedSize() {
        // A 64×64 PNG written here (never derived from any game asset)
        // so the test exercises real PNG header plumbing. Any Android
        // bitmap decoder would see the same dimensions.
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val black = ((x / 8) + (y / 8)) % 2 == 0
                img.setRGB(x, y, if (black) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        val f = File(tmp.newFolder("map"), "v.png")
        ImageIO.write(img, "png", f)
        assertTrue(f.length() > 0)
        val read = ImageIO.read(f)
        assertEquals(64, read.width)
        assertEquals(64, read.height)
    }
}
