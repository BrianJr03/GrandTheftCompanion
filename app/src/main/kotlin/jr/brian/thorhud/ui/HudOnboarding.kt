package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.OnboardingSlide

/**
 * The first-visit tour of the HUD page. Three slides on GTA IV, five
 * on GTA V — the two extra slides name Customs (vehicle-card long
 * press) and Ammu-Nation (weapon-card long press), both of which the
 * IV plugin does not expose.
 *
 * Pure function, no Compose imports, so it can be unit-tested without
 * instrumentation. The Compose layer hands this list to
 * [jr.brian.thorhud.ui.components.OnboardingSheet] when the sheet
 * opens for the first time.
 */
internal fun hudOnboardingSlides(isGtaV: Boolean): List<OnboardingSlide> {
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
    if (isGtaV) {
        slides.add(
            OnboardingSlide(
                titleRes = R.string.hud_onboarding_slide_customs_title,
                bodyRes = R.string.hud_onboarding_slide_customs_body,
            )
        )
        slides.add(
            OnboardingSlide(
                titleRes = R.string.hud_onboarding_slide_ammu_title,
                bodyRes = R.string.hud_onboarding_slide_ammu_body,
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
