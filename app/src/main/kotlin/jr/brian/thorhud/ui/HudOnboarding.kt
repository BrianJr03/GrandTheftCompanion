package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.OnboardingSlide

/**
 * The first-visit tour of the HUD page. Each long-press gesture
 * gets its own detailed slide (Wardrobe / Ammu-Nation / LS Customs)
 * instead of one summary slide — Compose's OnboardingSheet paints
 * the feature-name keywords in the episode accent so the slide
 * carries the connection between the card and the shop visually.
 *
 * Shape by game:
 *   V always:          HUD, tap, Wardrobe, Ammu-Nation, Customs, tabs
 *   IV + Wardrobe:     HUD, tap, Wardrobe, tabs
 *   IV without:        HUD, tap, tabs
 *
 * Pure function, no Compose imports, so it can be unit-tested
 * without instrumentation. The Compose layer hands this list to
 * [jr.brian.thorhud.ui.components.OnboardingSheet] alongside the
 * highlight keywords.
 *
 * [hasWardrobe] is the hello flag: an old plugin without Wardrobe
 * drops the Wardrobe slide so the sheet doesn't promise a gesture
 * the plugin can't serve.
 */
internal fun hudOnboardingSlides(
    isGtaV: Boolean,
    hasWardrobe: Boolean,
): List<OnboardingSlide> {
    val slides = mutableListOf(
        OnboardingSlide(
            titleRes = R.string.hud_onboarding_slide_hud_title,
            bodyRes = R.string.hud_onboarding_slide_hud_body,
        ),
        OnboardingSlide(
            titleRes = R.string.hud_onboarding_slide_tap_title,
            bodyRes = R.string.hud_onboarding_slide_tap_body,
        ),
    )
    // Wardrobe runs on both games once the plugin reports support.
    // The slide drops when hasWardrobe is false (old plugin on IV
    // today) so the sheet doesn't promise a gesture the plugin
    // can't serve.
    if (hasWardrobe) {
        slides.add(
            OnboardingSlide(
                titleRes = R.string.longpress_slide_wardrobe_title,
                bodyRes = R.string.longpress_slide_wardrobe_body,
            )
        )
    }
    // Ammu-Nation + LS Customs are V-only (no armory / mods natives
    // on the IV plugin). Order picks the on-foot gesture first,
    // matching the typical equip-then-drive play loop.
    if (isGtaV) {
        slides.add(
            OnboardingSlide(
                titleRes = R.string.longpress_slide_ammu_title,
                bodyRes = R.string.longpress_slide_ammu_body,
            )
        )
        slides.add(
            OnboardingSlide(
                titleRes = R.string.longpress_slide_customs_title,
                bodyRes = R.string.longpress_slide_customs_body,
            )
        )
    }
    slides.add(
        OnboardingSlide(
            titleRes = R.string.hud_onboarding_slide_tabs_title,
            bodyRes = R.string.hud_onboarding_slide_tabs_body,
        )
    )
    return slides
}
