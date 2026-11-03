package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins slide composition for the HUD-page first-visit sheet.
 *
 * Shape by game:
 *   V always:          HUD, tap, Wardrobe, Ammu-Nation, Customs, tabs
 *   IV + Wardrobe:     HUD, tap, Wardrobe, tabs
 *   IV without:        HUD, tap, tabs
 *
 * Ammu-Nation and LS Customs are V-only (no armory / mods natives on
 * the IV plugin). The Wardrobe slide drops when hasWardrobe is false
 * so an old plugin doesn't promise a gesture it can't serve.
 */
class HudOnboardingTest {

    @Test
    fun vListIsSixSlidesWithWardrobeAmmuAndCustomsAfterTap() {
        val slides = hudOnboardingSlides(isGtaV = true, hasWardrobe = true)
        assertEquals(6, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.longpress_slide_wardrobe_title, slides[2].titleRes)
        assertEquals(R.string.longpress_slide_ammu_title, slides[3].titleRes)
        assertEquals(R.string.longpress_slide_customs_title, slides[4].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[5].titleRes)
    }

    @Test
    fun vListStillHasAmmuAndCustomsWithoutWardrobe() {
        val slides = hudOnboardingSlides(isGtaV = true, hasWardrobe = false)
        assertEquals(5, slides.size)
        val titles = slides.map { it.titleRes }
        assertFalse(
            "hasWardrobe=false must drop the Wardrobe slide",
            R.string.longpress_slide_wardrobe_title in titles,
        )
        assertTrue(R.string.longpress_slide_ammu_title in titles)
        assertTrue(R.string.longpress_slide_customs_title in titles)
    }

    @Test
    fun ivListWithWardrobeAddsWardrobeSlideBeforeTabs() {
        val slides = hudOnboardingSlides(isGtaV = false, hasWardrobe = true)
        assertEquals(4, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.longpress_slide_wardrobe_title, slides[2].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[3].titleRes)
        val titles = slides.map { it.titleRes }
        assertFalse(
            "IV never ships the Ammu-Nation slide",
            R.string.longpress_slide_ammu_title in titles,
        )
        assertFalse(
            "IV never ships the LS Customs slide",
            R.string.longpress_slide_customs_title in titles,
        )
    }

    @Test
    fun ivListBeforeWardrobeDropsTheWardrobeSlide() {
        val slides = hudOnboardingSlides(isGtaV = false, hasWardrobe = false)
        assertEquals(3, slides.size)
        assertEquals(R.string.hud_onboarding_slide_hud_title, slides[0].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tap_title, slides[1].titleRes)
        assertEquals(R.string.hud_onboarding_slide_tabs_title, slides[2].titleRes)
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
