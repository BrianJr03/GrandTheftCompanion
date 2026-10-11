package jr.brian.thorhud.ui.components

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import jr.brian.thorhud.R

/**
 * Accent colours tied to the currently-loaded GTA IV episode. The ASI
 * reports `episode` via GET_CURRENT_EPISODE (0 = IV, 1 = The Lost and
 * Damned, 2 = The Ballad of Gay Tony). Each episode gets its own
 * signature accent so the HUD mirrors the mood of what's on the top
 * screen. `labelRes` is a string resource rather than a String so the
 * palette instances can live at file-top scope (which can't call
 * stringResource).
 */
internal data class HudPalette(
    @StringRes val labelRes: Int,
    val accent: Color,
    val speed: Color,
    val health: Color,
    val armor: Color,
    val stars: Color,
    val engine: Color,
)

internal val PaletteIV = HudPalette(
    labelRes = R.string.episode_iv,
    accent   = Color(0xFFEDEDED),  // base IV: clean white as the default accent
    speed    = Color(0xFFEDEDED),
    health   = Color(0xFFEDEDED),
    armor    = Color(0xFF4F8FD0),
    stars    = Color(0xFFE09B3C),
    engine   = Color(0xFFE09B3C),
)
internal val PaletteTLAD = HudPalette(
    labelRes = R.string.episode_tlad,
    accent   = Color(0xFFC46A2A),  // biker-rust orange
    speed    = Color(0xFFE2963B),
    health   = Color(0xFFC46A2A),
    armor    = Color(0xFF7A8E92),  // gunmetal
    stars    = Color(0xFFD9CF4A),
    engine   = Color(0xFFD9CF4A),
)
internal val PaletteTBoGT = HudPalette(
    labelRes = R.string.episode_tbogt,
    accent   = Color(0xFFFF4FBF),  // Tony neon-magenta
    speed    = Color(0xFFFF4FBF),
    health   = Color(0xFFFF8EDC),
    armor    = Color(0xFF59F0E8),  // cyan to complement the magenta
    stars    = Color(0xFFF8E850),  // gold
    engine   = Color(0xFFF8E850),
)

internal fun paletteFor(episode: Int?): HudPalette = when (episode) {
    1    -> PaletteTLAD
    2    -> PaletteTBoGT
    else -> PaletteIV
}
