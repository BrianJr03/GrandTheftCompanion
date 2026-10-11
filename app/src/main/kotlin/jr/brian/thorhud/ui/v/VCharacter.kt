package jr.brian.thorhud.ui.v

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.HudPalette

/**
 * Who the player currently is in GTA V story mode.
 *
 * GTA IV picks its accent palette from the loaded episode; GTA V has no
 * episodes but three protagonists the player switches between mid-
 * session, each with their own cash, weapons, health and special
 * ability. The plugin reports `character` (0/1/2, or -1 for anyone
 * else) and the whole HUD re-tints to match, using the colours the
 * game's own HUD assigns each character.
 */
internal enum class VCharacter(
    @StringRes val nameRes: Int,
    val palette: HudPalette,
    /** False for [Other]: no per-character cash stat, no special ability. */
    val isProtagonist: Boolean,
) {
    Michael(
        nameRes = R.string.v_char_michael,
        palette = HudPalette(
            labelRes = R.string.v_char_michael,
            accent   = Color(0xFF65B4D4),  // Michael's HUD blue
            speed    = Color(0xFF65B4D4),
            health   = Color(0xFF65B4D4),
            armor    = Color(0xFF8FA3B8),  // steel, so it reads apart from the blue
            stars    = Color(0xFFF2F2F2),  // GTA V wanted stars are white
            engine   = Color(0xFFE09B3C),
        ),
        isProtagonist = true,
    ),
    Franklin(
        nameRes = R.string.v_char_franklin,
        palette = HudPalette(
            labelRes = R.string.v_char_franklin,
            accent   = Color(0xFFABEDAB),  // Franklin's HUD green
            speed    = Color(0xFFABEDAB),
            health   = Color(0xFFABEDAB),
            armor    = Color(0xFF4F8FD0),
            stars    = Color(0xFFF2F2F2),
            engine   = Color(0xFFE09B3C),
        ),
        isProtagonist = true,
    ),
    Trevor(
        nameRes = R.string.v_char_trevor,
        palette = HudPalette(
            labelRes = R.string.v_char_trevor,
            accent   = Color(0xFFFFA357),  // Trevor's HUD orange
            speed    = Color(0xFFFFA357),
            health   = Color(0xFFFFA357),
            armor    = Color(0xFF4F8FD0),
            stars    = Color(0xFFF2F2F2),
            engine   = Color(0xFFD9CF4A),  // yellow, apart from the orange
        ),
        isProtagonist = true,
    ),

    /** Director Mode actor, peyote animal, or any ped that is not one
     *  of the three. Neutral palette; cash and special are hidden. */
    Other(
        nameRes = R.string.v_char_other,
        palette = HudPalette(
            labelRes = R.string.v_char_other,
            accent   = Color(0xFFEDEDED),
            speed    = Color(0xFFEDEDED),
            health   = Color(0xFFEDEDED),
            armor    = Color(0xFF4F8FD0),
            stars    = Color(0xFFF2F2F2),
            engine   = Color(0xFFE09B3C),
        ),
        isProtagonist = false,
    ),
}

internal fun vCharacterOf(id: Int?): VCharacter = when (id) {
    0    -> VCharacter.Michael
    1    -> VCharacter.Franklin
    2    -> VCharacter.Trevor
    else -> VCharacter.Other
}

/** The special-ability bar is yellow for all three characters in game. */
internal val VSpecialColor = Color(0xFFF0C850)
