package jr.brian.thorhud.ui.longpress

import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.OnboardingSlide

/**
 * Slides for the one-time "Hold to customize" OnboardingSheet. One
 * slide per long-press (character / weapon / vehicle); V gets all
 * three, IV gets just the character slide (Ammu-Nation and LS
 * Customs are V-only). An IV plugin without Wardrobe wired yet
 * returns an empty list, and the caller suppresses the sheet so a
 * dead prompt never shows.
 *
 * Pure Kotlin — [LongPressPromptTest] pins behaviour on a plain JVM.
 */
internal fun longPressOnboardingSlides(
    isGtaV: Boolean,
    hasWardrobe: Boolean,
): List<OnboardingSlide> {
    val out = mutableListOf<OnboardingSlide>()
    if (hasWardrobe) {
        out += OnboardingSlide(
            titleRes = R.string.longpress_slide_wardrobe_title,
            bodyRes = R.string.longpress_slide_wardrobe_body,
        )
    }
    if (isGtaV) {
        // V-only shops. Order (ammu → customs) is deliberate: the
        // on-foot card (weapon) comes before the in-vehicle card
        // (vehicle), matching the typical play loop of equip-first,
        // drive-later.
        out += OnboardingSlide(
            titleRes = R.string.longpress_slide_ammu_title,
            bodyRes = R.string.longpress_slide_ammu_body,
        )
        out += OnboardingSlide(
            titleRes = R.string.longpress_slide_customs_title,
            bodyRes = R.string.longpress_slide_customs_body,
        )
    }
    return out
}

/**
 * True when the pop-up has anything useful to say. On IV before
 * Wardrobe lands this is false, so [MainScreen] skips the sheet AND
 * keeps `hasSeenLongPressPrompt` unset — the next launch (likely with
 * Wardrobe wired) will show it exactly once.
 */
internal fun shouldShowLongPressPrompt(
    isGtaV: Boolean,
    hasWardrobe: Boolean,
): Boolean = longPressOnboardingSlides(isGtaV, hasWardrobe).isNotEmpty()
