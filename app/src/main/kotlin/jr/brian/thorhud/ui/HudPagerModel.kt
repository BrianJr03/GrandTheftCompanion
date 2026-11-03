package jr.brian.thorhud.ui

import androidx.annotation.StringRes
import jr.brian.thorhud.R

/**
 * The pager's four possible tabs. Order is the left-to-right tab layout.
 * HUD stays in the middle of the always-present three (one swipe either
 * way) and is the default landing page. Atlas is the fourth tab and is
 * only present when [hudPages] is called with [hasAtlas] = true.
 *
 * Call sites never read [HudPage.ordinal] directly — go through
 * [hudPages] + [currentHudPage] / [scrollTargetFor] so adding or
 * removing a tab (or turning endless swiping on) doesn't require
 * touching every "page == something" branch.
 */
enum class HudPage(@StringRes val labelRes: Int) {
    Cheats(R.string.common_tab_cheats),
    Hud(R.string.tab_hud),
    Map(R.string.common_tab_map),
    Atlas(R.string.tab_atlas),
}

/**
 * The ordered page list the pager actually renders. Dropping Atlas is
 * cheaper than inserting a "disabled" page because the pager's page
 * count is this list's size and all index arithmetic reads from the
 * same list.
 */
fun hudPages(hasAtlas: Boolean): List<HudPage> =
    if (hasAtlas) HudPage.entries.toList()
    else HudPage.entries.filter { it != HudPage.Atlas }

/**
 * How many virtual pages to show when endless swiping is on. Chosen big
 * enough that a user can swipe for a very long time before hitting the
 * middle-lap recentring, but not so big that Compose's HorizontalPager
 * (which pre-composes neighbors) misbehaves. Int.MAX_VALUE was the
 * other obvious pick; `1_000` keeps virtual indices small so arithmetic
 * stays in Int range even if the lap counter drifts.
 */
const val HUD_PAGER_LAPS = 1_000

/** Virtual page count for the current configuration. */
fun pagerPageCount(pages: List<HudPage>, endless: Boolean): Int =
    if (endless) pages.size * HUD_PAGER_LAPS else pages.size

/**
 * Which [HudPage] a virtual pager index resolves to. Returns null if
 * the list is empty or the index is somehow out of bounds (should
 * never happen with the pager wired correctly, but it keeps call sites
 * null-safe without a stray `!!`).
 */
fun currentHudPage(virtualIndex: Int, pages: List<HudPage>): HudPage? {
    if (pages.isEmpty()) return null
    val n = pages.size
    val mod = ((virtualIndex % n) + n) % n
    return pages.getOrNull(mod)
}

/** True when [virtualIndex] resolves to [page] in the current list. */
fun isHudPage(virtualIndex: Int, pages: List<HudPage>, page: HudPage): Boolean =
    currentHudPage(virtualIndex, pages) == page

/**
 * Virtual index the pager should land on when the view first appears.
 * Non-endless: just the plain index of [initial]. Endless: the matching
 * virtual slot in the middle lap, so there is as much room to swipe
 * left as right before the user hits either end.
 */
fun initialVirtualPage(
    pages: List<HudPage>,
    endless: Boolean,
    initial: HudPage = HudPage.Hud,
): Int {
    val realIndex = pages.indexOf(initial).coerceAtLeast(0)
    if (!endless) return realIndex
    val middleLap = HUD_PAGER_LAPS / 2
    return middleLap * pages.size + realIndex
}

/**
 * The virtual index the pager should scroll to when the user asks for
 * [target]. Non-endless: the plain real index. Endless: whichever
 * virtual slot for [target] is reachable in the fewest steps from
 * [currentVirtual]. "Nearest" matters because
 * `animateScrollToPage(newIndex)` scrolls through every page in between
 * — jumping from Cheats to Map by adding `pages.size` would smear
 * several hundred milliseconds of animation. Picking the nearer side
 * keeps every jump at most `pages.size / 2` pages away.
 */
fun scrollTargetFor(
    currentVirtual: Int,
    target: HudPage,
    pages: List<HudPage>,
    endless: Boolean,
): Int {
    val realTarget = pages.indexOf(target)
    if (realTarget < 0) return currentVirtual
    if (!endless) return realTarget
    val n = pages.size
    // Candidate virtual indices for the target in the current lap and
    // the two neighbouring laps. The nearest wins; ties go to the
    // candidate above `currentVirtual` so a right-swipe animation is
    // the default when the two options are symmetric.
    val lap = currentVirtual / n
    val candidates = intArrayOf(
        (lap - 1) * n + realTarget,
        lap * n + realTarget,
        (lap + 1) * n + realTarget,
    )
    var best = candidates[0]
    var bestDist = kotlin.math.abs(best - currentVirtual)
    for (i in 1 until candidates.size) {
        val d = kotlin.math.abs(candidates[i] - currentVirtual)
        if (d < bestDist || (d == bestDist && candidates[i] > best)) {
            best = candidates[i]
            bestDist = d
        }
    }
    return best
}

/**
 * Virtual index to settle on when endless swiping flips on while the
 * pager is sitting on [currentNonEndless]. We're jumping from "page
 * index == real index" to the middle lap, so the user stays on the
 * same real page and keeps nearly a full lap of swipe room in both
 * directions.
 */
fun recentreOnEnableEndless(
    currentNonEndless: Int,
    pages: List<HudPage>,
): Int {
    if (pages.isEmpty()) return 0
    val n = pages.size
    val real = ((currentNonEndless % n) + n) % n
    val middleLap = HUD_PAGER_LAPS / 2
    return middleLap * n + real
}

/**
 * Virtual index to settle on when endless swiping flips off. Collapses
 * the virtual index back to its real-page position so the non-endless
 * pager (page count = pages.size) stays in-bounds.
 */
fun recentreOnDisableEndless(
    currentVirtual: Int,
    pages: List<HudPage>,
): Int {
    if (pages.isEmpty()) return 0
    val n = pages.size
    return ((currentVirtual % n) + n) % n
}

/**
 * How close to either end of the virtual range the pager is willing to
 * drift before we silently recentre into the middle lap. Half of this
 * on each side still gives several hundred laps of swipe room, so the
 * recentre only fires after an unusually long session.
 */
private const val DRIFT_LAP_MARGIN = 50

/**
 * When endless swiping is on and the pager has drifted close to either
 * end of its virtual range, returns the matching virtual index in the
 * middle lap so the caller can snap-scroll silently. Returns null if
 * the drift is still comfortably inside the valid range (which it is
 * for any realistic session).
 */
fun recentreIfDrifting(
    currentVirtual: Int,
    pages: List<HudPage>,
    endless: Boolean,
): Int? {
    if (!endless || pages.isEmpty()) return null
    val n = pages.size
    val lap = currentVirtual / n
    val total = HUD_PAGER_LAPS
    if (lap >= DRIFT_LAP_MARGIN && lap < total - DRIFT_LAP_MARGIN) return null
    val real = ((currentVirtual % n) + n) % n
    val middleLap = total / 2
    return middleLap * n + real
}
