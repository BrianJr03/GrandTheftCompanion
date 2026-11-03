package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins slide composition for the HUD-page first-visit sheet.
 *
 * GTA V ships with four slides (HUD, tap, hold, tabs). GTA IV ships
 * with three when Wardrobe is wired (HUD, tap, hold, tabs → tabs sits
 * in slot 3 after the hold slide) or two before Wardrobe (hasWardrobe
 * false): the hold slide drops because there is nothing to hold yet.
 * The old Customs / Ammu-Nation slides are gone — their body lives
 * inside the shared "hold to customise" slide for V.
 */
class HudOnboardingTest {

    @Test
    fun vListIsFourSlidesWithHoldAfterTap() {
        val slides = hudOnboardingSlides(isGtaV = true, hasWardrobe = true)
        assertEquals(4, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.hud_onboarding_slide_hold_title, slides[2].titleRes)
        assertEquals(R.string.hud_onboarding_slide_hold_body_v, slides[2].bodyRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[3].titleRes)
    }

    @Test
    fun vListStillHasHoldSlideWithoutWardrobeBecauseCustomsPlusAmmuRemain() {
        val slides = hudOnboardingSlides(isGtaV = true, hasWardrobe = false)
        assertEquals(4, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hold_title, slides[2].titleRes)
        // Same body on V regardless of hasWardrobe: the V hold slide
        // names Customs + Ammu-Nation + Wardrobe. "Wardrobe" in the
        // body is harmless when the plugin's old; the gesture just
        // reads as nothing to do until the user updates.
        assertEquals(R.string.hud_onboarding_slide_hold_body_v, slides[2].bodyRes)
    }

    @Test
    fun ivListWithWardrobeAddsHoldSlideBeforeTabs() {
        // IV with Wardrobe is three base slides (hud, tap, tabs) +
        // one conditional (hold) = four. Without Wardrobe the hold
        // slide drops back to three — see
        // [ivListBeforeWardrobeDropsTheHoldSlide].
        val slides = hudOnboardingSlides(isGtaV = false, hasWardrobe = true)
        assertEquals(4, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.hud_onboarding_slide_hold_title, slides[2].titleRes)
        assertEquals(R.string.hud_onboarding_slide_hold_body_iv, slides[2].bodyRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[3].titleRes)
    }

    @Test
    fun ivListBeforeWardrobeDropsTheHoldSlide() {
        val slides = hudOnboardingSlides(isGtaV = false, hasWardrobe = false)
        assertEquals(3, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[2].titleRes)
        val titles = slides.map { it.titleRes }
        assertFalse(
            "IV before Wardrobe has nothing to hold; the hold slide must drop",
            R.string.hud_onboarding_slide_hold_title in titles,
        )
    }

    @Test
    fun ivListPicksTheIvHoldBody() {
        val slides = hudOnboardingSlides(isGtaV = false, hasWardrobe = true)
        val hold = slides.first { it.titleRes == R.string.hud_onboarding_slide_hold_title }
        assertEquals(R.string.hud_onboarding_slide_hold_body_iv, hold.bodyRes)
    }

    @Test
    fun bodyMatchesTitleForEverySlide() {
        for (isV in listOf(false, true)) {
            for (hasW in listOf(false, true)) {
                for (slide in hudOnboardingSlides(isGtaV = isV, hasWardrobe = hasW)) {
                    assertTrue(
                        "slide ${slide.titleRes} must have a body res",
                        slide.bodyRes != 0,
                    )
                }
            }
        }
    }
}
