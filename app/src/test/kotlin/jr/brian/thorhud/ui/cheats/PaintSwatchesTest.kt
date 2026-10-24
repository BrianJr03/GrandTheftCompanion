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
}
