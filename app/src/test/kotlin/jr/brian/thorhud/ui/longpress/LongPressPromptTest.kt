package jr.brian.thorhud.ui.longpress

import jr.brian.thorhud.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which lines show in the one-time "Hold to customise" prompt
 * for each (game, hasWardrobe) combo. The ring line is only present
 * when there is at least one gesture line to pair it with — a prompt
 * that said only "a glowing ring marks what you can hold" with
 * nothing to hold would be nonsense.
 */
class LongPressPromptTest {

    @Test
    fun vWithWardrobeGetsAllFourLines() {
        val lines = longPressPromptLines(isGtaV = true, hasWardrobe = true)
        assertEquals(
            listOf(
                R.string.longpress_prompt_wardrobe,
                R.string.longpress_prompt_ammu,
                R.string.longpress_prompt_customs,
                R.string.longpress_prompt_ring,
            ),
            lines,
        )
    }

    @Test
    fun vWithoutWardrobeDropsOnlyTheWardrobeLine() {
        // Not a real state today — V ships with Wardrobe in Part 3 —
        // but the function still has to pick the right rows for a
        // future build that flips it off.
        val lines = longPressPromptLines(isGtaV = true, hasWardrobe = false)
        assertEquals(
            listOf(
                R.string.longpress_prompt_ammu,
                R.string.longpress_prompt_customs,
                R.string.longpress_prompt_ring,
            ),
            lines,
        )
    }

    @Test
    fun ivWithWardrobeShowsWardrobeAndRingOnly() {
        val lines = longPressPromptLines(isGtaV = false, hasWardrobe = true)
        assertEquals(
            listOf(
                R.string.longpress_prompt_wardrobe,
                R.string.longpress_prompt_ring,
            ),
            lines,
        )
    }

    @Test
    fun ivBeforeWardrobeIsEmptyAndSuppresses() {
        val lines = longPressPromptLines(isGtaV = false, hasWardrobe = false)
        assertTrue("No lines on IV before Wardrobe", lines.isEmpty())
        assertFalse(
            "Prompt is suppressed on IV before Wardrobe",
            shouldShowLongPressPrompt(isGtaV = false, hasWardrobe = false),
        )
    }

    @Test
    fun shouldShowPromptTracksLineCount() {
        assertTrue(shouldShowLongPressPrompt(isGtaV = true, hasWardrobe = true))
        assertTrue(shouldShowLongPressPrompt(isGtaV = true, hasWardrobe = false))
        assertTrue(shouldShowLongPressPrompt(isGtaV = false, hasWardrobe = true))
        assertFalse(shouldShowLongPressPrompt(isGtaV = false, hasWardrobe = false))
    }
}
