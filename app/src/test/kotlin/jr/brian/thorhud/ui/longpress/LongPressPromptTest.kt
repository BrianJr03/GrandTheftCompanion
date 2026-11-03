package jr.brian.thorhud.ui.longpress

import jr.brian.thorhud.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which slides show in the one-time "Hold to customize"
 * OnboardingSheet for each (game, hasWardrobe) combo. The sheet
 * replaces the earlier text-wall InfoSheet and the discovery-ring
 * animation on the HUD-card labels; one slide per gesture lets the
 * sheet carry a screenshot or longer body copy per card.
 */
class LongPressPromptTest {

    @Test
    fun vWithWardrobeGetsAllThreeSlides() {
        val slides = longPressOnboardingSlides(isGtaV = true, hasWardrobe = true)
        assertEquals(3, slides.size)
        assertEquals(R.string.longpress_slide_wardrobe_title, slides[0].titleRes)
        assertEquals(R.string.longpress_slide_ammu_title,     slides[1].titleRes)
        assertEquals(R.string.longpress_slide_customs_title,  slides[2].titleRes)
    }

    @Test
    fun vWithoutWardrobeDropsOnlyTheWardrobeSlide() {
        // Not a real state today — V ships with Wardrobe in Part 3 —
        // but the function still picks the right subset for a future
        // build that flips it off.
        val slides = longPressOnboardingSlides(isGtaV = true, hasWardrobe = false)
        assertEquals(2, slides.size)
        assertEquals(R.string.longpress_slide_ammu_title,    slides[0].titleRes)
        assertEquals(R.string.longpress_slide_customs_title, slides[1].titleRes)
    }

    @Test
    fun ivWithWardrobeShowsOnlyTheWardrobeSlide() {
        val slides = longPressOnboardingSlides(isGtaV = false, hasWardrobe = true)
        assertEquals(1, slides.size)
        assertEquals(R.string.longpress_slide_wardrobe_title, slides[0].titleRes)
    }

    @Test
    fun ivBeforeWardrobeIsEmptyAndSuppresses() {
        val slides = longPressOnboardingSlides(isGtaV = false, hasWardrobe = false)
        assertTrue("No slides on IV before Wardrobe", slides.isEmpty())
        assertFalse(
            "Prompt is suppressed on IV before Wardrobe",
            shouldShowLongPressPrompt(isGtaV = false, hasWardrobe = false),
        )
    }

    @Test
    fun shouldShowPromptTracksSlideCount() {
        assertTrue(shouldShowLongPressPrompt(isGtaV = true, hasWardrobe = true))
        assertTrue(shouldShowLongPressPrompt(isGtaV = true, hasWardrobe = false))
        assertTrue(shouldShowLongPressPrompt(isGtaV = false, hasWardrobe = true))
        assertFalse(shouldShowLongPressPrompt(isGtaV = false, hasWardrobe = false))
    }
}
