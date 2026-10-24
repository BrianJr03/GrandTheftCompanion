package jr.brian.thorhud.ui

import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.OnboardingSlide

/**
 * The first-visit tour of the map tab. Three slides covering what
 * the screen does, the pan/zoom/lock gestures, and the icon column
 * that reaches the other tabs without leaving the map.
 *
 * Pure function, no Compose imports, so it can be unit-tested without
 * instrumentation. The Compose layer hands this list to
 * [jr.brian.thorhud.ui.components.OnboardingSheet] on first visit.
 */
internal fun mapOnboardingSlides(): List<OnboardingSlide> = listOf(
    OnboardingSlide(
        titleRes = R.string.map_onboarding_slide_overview_title,
        bodyRes = R.string.map_onboarding_slide_overview_body,
    ),
    OnboardingSlide(
        titleRes = R.string.map_onboarding_slide_gestures_title,
        bodyRes = R.string.map_onboarding_slide_gestures_body,
    ),
    OnboardingSlide(
        titleRes = R.string.map_onboarding_slide_actions_title,
        bodyRes = R.string.map_onboarding_slide_actions_body,
    ),
)
