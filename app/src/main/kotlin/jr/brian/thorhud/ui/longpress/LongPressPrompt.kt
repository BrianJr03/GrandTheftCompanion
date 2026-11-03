package jr.brian.thorhud.ui.longpress

import jr.brian.thorhud.R

/**
 * The one-time "Hold to customise" pop-up's body is three short lines
 * built from these entries. GTA V with Wardrobe gets all four; GTA IV
 * with Wardrobe gets Wardrobe + the ring line; GTA IV before Wardrobe
 * is on (`hasWardrobe = false`) gets an empty list, and the caller
 * suppresses the sheet entirely so a dead prompt never shows.
 *
 * No Compose or Android imports here — [LongPressPromptLinesTest]
 * pins behaviour on a plain JVM.
 */
fun longPressPromptLines(
    isGtaV: Boolean,
    hasWardrobe: Boolean,
): List<Int> {
    val out = mutableListOf<Int>()
    if (hasWardrobe) {
        out += R.string.longpress_prompt_wardrobe
    }
    if (isGtaV) {
        // Ammu-Nation and LS Customs are V-only in v1.
        out += R.string.longpress_prompt_ammu
        out += R.string.longpress_prompt_customs
    }
    if (out.isNotEmpty()) {
        out += R.string.longpress_prompt_ring
    }
    return out
}

/**
 * True when the pop-up has anything useful to say. On IV before
 * Wardrobe lands this is false, so [MainScreen] skips the sheet AND
 * keeps `hasSeenLongPressPrompt` unset — the next launch (likely with
 * Wardrobe wired) will show it exactly once.
 */
fun shouldShowLongPressPrompt(
    isGtaV: Boolean,
    hasWardrobe: Boolean,
): Boolean = longPressPromptLines(isGtaV, hasWardrobe).isNotEmpty()
