package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.OnboardingSlide

/**
 * The first-visit tour of the HUD page. Four slides on GTA V, three
 * on GTA IV when Wardrobe is wired (two before — see [hasWardrobe]).
 * The old pencil-icon slides for Customs and Ammu-Nation are gone;
 * one shared "hold to customise" slide names the long-presses with
 * a per-game body.
 *
 * Pure function, no Compose imports, so it can be unit-tested without
 * instrumentation. The Compose layer hands this list to
 * [jr.brian.thorhud.ui.components.OnboardingSheet] when the sheet
 * opens for the first time.
 *
 * [hasWardrobe] is the hello flag: an old plugin without Wardrobe
 * drops the hold slide on IV (nothing to hold yet) and keeps it on V
 * (Customs and Ammu-Nation still land there).
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
    // V always has Customs + Ammu-Nation to point at, so the hold
    // slide ships regardless. IV only has Wardrobe to point at, so
    // the slide skips when the plugin isn't new enough to serve it.
    if (isGtaV) {
        slides.add(
            OnboardingSlide(
                titleRes = R.string.hud_onboarding_slide_hold_title,
                bodyRes = R.string.hud_onboarding_slide_hold_body_v,
            )
        )
    } else if (hasWardrobe) {
        slides.add(
            OnboardingSlide(
                titleRes = R.string.hud_onboarding_slide_hold_title,
                bodyRes = R.string.hud_onboarding_slide_hold_body_iv,
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
