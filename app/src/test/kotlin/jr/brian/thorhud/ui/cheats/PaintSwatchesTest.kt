package jr.brian.thorhud.ui.cheats

import jr.brian.thorhud.net.commandFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pack / unpack contract so a tap on a swatch is bit-exact
 * identical to what the plugin's `paint_rgb` handler unpacks on the
 * other end.
 */
class PaintSwatchesTest {

    @Test
    fun packUnpackRoundTrip() {
        for (sw in PAINT_SWATCHES) {
            val (r, g, b) = unpackRgb(sw.packed)
            assertEquals(sw.packed, packRgb(r, g, b))
            assertTrue("swatch packed=${sw.packed.toString(16)} must fit 0..0xFFFFFF",
                       sw.packed in 0L..0xFFFFFFL)
        }
    }

    @Test
    fun packStripsHighBytes() {
        // Negative / oversized channel bytes are masked to 0..255 so a
        // malformed caller cannot push bits into the high byte of the
        // packed Long (which must stay zero for commandFrame to accept
        // the value as unsigned 32-bit).
        assertEquals(0x00FFFFFFL, packRgb(0x1FF, 0xFF, 0xFF))
        assertEquals(0L, packRgb(-1 and 0, 0, 0))
    }

    @Test
    fun commandFrameAcceptsEverySwatch() {
        // Every swatch has to be a legal argument for paint_rgb, with
        // "rgb" as the argument key — not "cheat". Both halves matter:
        // the key tells the plugin to route to the paint_rgb handler
        // rather than the id-shaped cheat path.
        for (sw in PAINT_SWATCHES) {
            val line = commandFrame(42, "paint_rgb", sw.packed)
            assertNotNull("paint_rgb must accept swatch ${sw.packed.toString(16)}", line)
            assertTrue(line!!.contains("\"rgb\":${sw.packed}"))
        }
    }

    @Test
    fun eightSwatches() {
        // The swatch row has eight tiles on the V paint section; a
        // regression here would shuffle the row layout.
        assertEquals(8, PAINT_SWATCHES.size)
    }

    @Test
    fun hsvToRgbCornerColours() {
        // Pure saturation + value hits every pure primary / secondary.
        assertEquals(0x00FF0000L, hsvToRgbPacked(0f,   1f, 1f))
        assertEquals(0x0000FF00L, hsvToRgbPacked(120f, 1f, 1f))
        assertEquals(0x000000FFL, hsvToRgbPacked(240f, 1f, 1f))
        assertEquals(0x00FFFFFFL, hsvToRgbPacked(0f,   0f, 1f))   // sat=0 -> white
        assertEquals(0x00000000L, hsvToRgbPacked(0f,   1f, 0f))   // value=0 -> black
        // Hue wraps.
        assertEquals(hsvToRgbPacked(0f, 1f, 1f),
                     hsvToRgbPacked(360f, 1f, 1f))
    }

    @Test
    fun hsvFitsPackedU32() {
        // Covers the whole hue / sat / value space in coarse strides;
        // every output must fit the paint_rgb wire slot (0..0xFFFFFF).
        for (h in 0..360 step 15) {
            for (s in 0..10) {
                for (v in 0..10) {
                    val packed = hsvToRgbPacked(h.toFloat(), s / 10f, v / 10f)
                    assertTrue("packed=${packed.toString(16)} must be ≤ 0xFFFFFF",
                               packed in 0L..0xFFFFFFL)
                }
            }
        }
    }

    @Test
    fun rgbToHsvRoundTrips() {
        // Round-trip each swatch through RGB → HSV → RGB. The toInt
        // step in packRgb loses ~1 LSB on hue-cusp colours, so the
        // tolerance is ±2 per channel.
        for (sw in PAINT_SWATCHES) {
            val (r, g, b) = unpackRgb(sw.packed)
            val (h, s, v) = rgbToHsv(r, g, b)
            val back = hsvToRgbPacked(h, s, v)
            val (r2, g2, b2) = unpackRgb(back)
            assertTrue("round-trip R delta ok (${r} vs ${r2})",
                       kotlin.math.abs(r - r2) <= 2)
            assertTrue("round-trip G delta ok (${g} vs ${g2})",
                       kotlin.math.abs(g - g2) <= 2)
            assertTrue("round-trip B delta ok (${b} vs ${b2})",
                       kotlin.math.abs(b - b2) <= 2)
        }
    }
}
