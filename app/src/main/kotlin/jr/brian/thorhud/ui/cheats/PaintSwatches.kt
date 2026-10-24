package jr.brian.thorhud.ui.cheats

import androidx.annotation.StringRes
import jr.brian.thorhud.R

/**
 * The eight custom-paint swatches shown on the GTA V paint section.
 *
 * These are our picks, not Rockstar's palette entries: the game's own
 * `carcols` names are data from the game, we don't ship them. Values
 * are packed `0x00RRGGBB` so [packRgb] / [unpackRgb] work the same way
 * the wire format does — one unsigned 32-bit field under `rgb` on the
 * `paint_rgb` command.
 *
 * Pure Kotlin, no Android imports, so the pack / unpack and the
 * invariants (never above 0xFFFFFF, round-trip through
 * [jr.brian.thorhud.net.commandFrame]) are unit-testable in
 * app/src/test/.../PaintSwatchesTest.kt.
 */
internal data class PaintSwatch(
    val packed: Long,
    @StringRes val labelRes: Int,
)

internal val PAINT_SWATCHES: List<PaintSwatch> = listOf(
    PaintSwatch(packRgb(0x0A, 0x0A, 0x0A), R.string.swatch_night),
    PaintSwatch(packRgb(0xF2, 0xF2, 0xF2), R.string.swatch_bone),
    PaintSwatch(packRgb(0xB2, 0x1E, 0x1E), R.string.swatch_red),
    PaintSwatch(packRgb(0x1E, 0x4A, 0xB2), R.string.swatch_blue),
    PaintSwatch(packRgb(0x1E, 0x4A, 0x2A), R.string.swatch_racing_green),
    PaintSwatch(packRgb(0xE0, 0x7A, 0x1E), R.string.swatch_orange),
    PaintSwatch(packRgb(0x6A, 0x2E, 0xA0), R.string.swatch_purple),
    PaintSwatch(packRgb(0x9A, 0x9A, 0xA2), R.string.swatch_chrome),
)

/** Pack three bytes 0..255 into one `0x00RRGGBB` Long. The high byte
 *  is always zero so the result fits an unsigned 32-bit int, which is
 *  all `paint_rgb` carries. */
internal fun packRgb(r: Int, g: Int, b: Int): Long {
    val rr = (r and 0xFF).toLong()
    val gg = (g and 0xFF).toLong()
    val bb = (b and 0xFF).toLong()
    return (rr shl 16) or (gg shl 8) or bb
}

/** Reverse of [packRgb]. Returns (r, g, b), each 0..255. */
internal fun unpackRgb(packed: Long): Triple<Int, Int, Int> {
    val r = ((packed ushr 16) and 0xFF).toInt()
    val g = ((packed ushr 8) and 0xFF).toInt()
    val b = (packed and 0xFF).toInt()
    return Triple(r, g, b)
}
