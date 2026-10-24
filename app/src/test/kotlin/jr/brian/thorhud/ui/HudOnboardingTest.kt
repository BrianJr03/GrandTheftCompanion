package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins slide composition for the HUD-page first-visit sheet.
 *
 * The GTA IV plugin cannot open Customs (no mods natives in IV), so
 * the Customs slide is V-only. The IV list is three slides; the V
 * list is four, with Customs in slot 2.
 */
class HudOnboardingTest {

    @Test
    fun ivListIsThreeSlidesInExpectedOrder() {
        val slides = hudOnboardingSlides(isGtaV = false)
        assertEquals(3, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[2].titleRes)
    }

    @Test
    fun vListIsFourSlidesWithCustomsInSlotTwo() {
        val slides = hudOnboardingSlides(isGtaV = true)
        assertEquals(4, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.hud_onboarding_slide_customs_title, slides[2].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[3].titleRes)
    }

    @Test
    fun ivListExcludesCustomsSlide() {
        val titles = hudOnboardingSlides(isGtaV = false).map { it.titleRes }
        assertFalse(
            "IV must not include the Customs slide — the IV plugin can't open Customs.",
            R.string.hud_onboarding_slide_customs_title in titles,
        )
    }

    @Test
    fun bodyMatchesTitleForEverySlide() {
        for (isV in listOf(false, true)) {
            for (slide in hudOnboardingSlides(isGtaV = isV)) {
                assertTrue(
                    "slide ${slide.titleRes} must have a body res",
                    slide.bodyRes != 0,
                )
            }
        }
    }
}
