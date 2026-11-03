package jr.brian.thorhud.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure page-index arithmetic that the pager + MainScreen go
 * through. Covers both the setting-off path (page == real index) and
 * the endless-on path (virtual middle lap, wrap, nearest-virtual
 * scroll target).
 */
class HudPagerModelTest {

    private val threePages = hudPages(hasAtlas = false)
    private val fourPages = hudPages(hasAtlas = true)

    @Test
    fun threePageListMatchesAtlasOffOrder() {
        assertEquals(listOf(HudPage.Cheats, HudPage.Hud, HudPage.Map), threePages)
    }

    @Test
    fun fourPageListIncludesAtlas() {
        assertEquals(
            listOf(HudPage.Cheats, HudPage.Hud, HudPage.Map, HudPage.Atlas),
            fourPages,
        )
    }

    @Test
    fun pagerCountReturnsListSizeWhenEndlessOff() {
        assertEquals(3, pagerPageCount(threePages, endless = false))
        assertEquals(4, pagerPageCount(fourPages, endless = false))
    }

    @Test
    fun pagerCountIsLapsTimesPagesWhenEndlessOn() {
        assertEquals(3 * HUD_PAGER_LAPS, pagerPageCount(threePages, endless = true))
        assertEquals(4 * HUD_PAGER_LAPS, pagerPageCount(fourPages, endless = true))
    }

    @Test
    fun initialVirtualPageIsRealIndexWhenEndlessOff() {
        assertEquals(1, initialVirtualPage(threePages, endless = false, HudPage.Hud))
        assertEquals(0, initialVirtualPage(threePages, endless = false, HudPage.Cheats))
        assertEquals(2, initialVirtualPage(threePages, endless = false, HudPage.Map))
    }

    @Test
    fun initialVirtualPageLandsInMiddleLapWhenEndlessOn() {
        val mid = HUD_PAGER_LAPS / 2
        val expectedHud = mid * threePages.size + 1
        assertEquals(expectedHud, initialVirtualPage(threePages, endless = true, HudPage.Hud))
        val expectedCheats = mid * threePages.size
        assertEquals(
            expectedCheats,
            initialVirtualPage(threePages, endless = true, HudPage.Cheats),
        )
    }

    @Test
    fun currentHudPageWrapsBothDirections() {
        // Three pages: index 0 Cheats, 1 Hud, 2 Map
        assertEquals(HudPage.Cheats, currentHudPage(0, threePages))
        assertEquals(HudPage.Hud, currentHudPage(1, threePages))
        assertEquals(HudPage.Map, currentHudPage(2, threePages))
        // Positive wrap: 3 → Cheats, 7 → Hud, 8 → Map
        assertEquals(HudPage.Cheats, currentHudPage(3, threePages))
        assertEquals(HudPage.Hud, currentHudPage(7, threePages))
        assertEquals(HudPage.Map, currentHudPage(8, threePages))
        // Negative wrap: -1 → Map, -2 → Hud, -3 → Cheats
        assertEquals(HudPage.Map, currentHudPage(-1, threePages))
        assertEquals(HudPage.Hud, currentHudPage(-2, threePages))
        assertEquals(HudPage.Cheats, currentHudPage(-3, threePages))
    }

    @Test
    fun currentHudPageReturnsNullForEmptyList() {
        assertNull(currentHudPage(0, emptyList()))
    }

    @Test
    fun isHudPageMatchesCurrentHudPage() {
        assertTrue(isHudPage(1, threePages, HudPage.Hud))
        assertTrue(isHudPage(4, threePages, HudPage.Hud))
        assertTrue(isHudPage(-2, threePages, HudPage.Hud))
    }

    @Test
    fun scrollTargetForNonEndlessReturnsRealIndex() {
        assertEquals(2, scrollTargetFor(1, HudPage.Map, threePages, endless = false))
        assertEquals(0, scrollTargetFor(2, HudPage.Cheats, threePages, endless = false))
    }

    @Test
    fun scrollTargetForEndlessPicksNearestVirtualFromEitherSide() {
        val mid = HUD_PAGER_LAPS / 2
        val baseHud = mid * threePages.size + 1 // Hud in middle lap
        // Hud → Cheats: adjacent left slot, distance 1
        val toCheats = scrollTargetFor(baseHud, HudPage.Cheats, threePages, endless = true)
        assertEquals(baseHud - 1, toCheats)
        // Hud → Map: adjacent right slot, distance 1
        val toMap = scrollTargetFor(baseHud, HudPage.Map, threePages, endless = true)
        assertEquals(baseHud + 1, toMap)
        // Cheats (left end of a lap) → Map should wrap backwards (prev-lap Map) because
        // the next-lap Cheats is further; three-page gap from Cheats to Map in-lap is 2.
        val fromCheats = mid * threePages.size
        val cheatsToMap = scrollTargetFor(fromCheats, HudPage.Map, threePages, endless = true)
        assertEquals(fromCheats - 1, cheatsToMap)
    }

    @Test
    fun scrollTargetForEndlessWithFourPagesWraps() {
        val mid = HUD_PAGER_LAPS / 2
        val baseHud = mid * fourPages.size + 1 // Hud in middle lap
        // Hud → Atlas (index 3): in-lap distance is 2 (Hud→Map→Atlas),
        // prev-lap Atlas is 2 behind too — tie, deterministic choice is
        // the higher candidate (right-swipe animation).
        val toAtlas = scrollTargetFor(baseHud, HudPage.Atlas, fourPages, endless = true)
        assertEquals(baseHud + 2, toAtlas)
        // Atlas → Hud: Atlas (index 3), next-lap Hud is (next-lap)+1 → 2 away forward;
        // in-lap Hud is 2 behind. Tie → forward wins.
        val baseAtlas = mid * fourPages.size + 3
        val atlasToHud = scrollTargetFor(baseAtlas, HudPage.Hud, fourPages, endless = true)
        assertEquals(baseAtlas + 2, atlasToHud)
    }

    @Test
    fun scrollTargetForEndlessStandingOnTargetReturnsSameIndex() {
        val mid = HUD_PAGER_LAPS / 2
        val base = mid * threePages.size + 1
        assertEquals(base, scrollTargetFor(base, HudPage.Hud, threePages, endless = true))
    }

    @Test
    fun recentreOnEnableKeepsRealPageWhileLandingInMiddleLap() {
        val mid = HUD_PAGER_LAPS / 2
        // Sitting on real Map (ordinal 2) with endless off, flipping on
        // should land us on Map in the middle lap.
        val next = recentreOnEnableEndless(2, threePages)
        assertEquals(HudPage.Map, currentHudPage(next, threePages))
        assertEquals(mid * threePages.size + 2, next)
    }

    @Test
    fun recentreOnDisableCollapsesToRealIndex() {
        val mid = HUD_PAGER_LAPS / 2
        val from = mid * threePages.size + 1 // Hud in middle lap
        assertEquals(1, recentreOnDisableEndless(from, threePages))
        val fromMap = mid * threePages.size + 2
        assertEquals(2, recentreOnDisableEndless(fromMap, threePages))
    }

    @Test
    fun recentreIfDriftingReturnsNullInMiddleOfRange() {
        val mid = HUD_PAGER_LAPS / 2
        assertNull(recentreIfDrifting(mid * threePages.size + 1, threePages, endless = true))
    }

    @Test
    fun recentreIfDriftingRecentersNearEnds() {
        val near = recentreIfDrifting(2, threePages, endless = true)
        assertNotNull(near)
        val mid = HUD_PAGER_LAPS / 2
        // Was on Map (ordinal 2); after recentre, still Map, in middle lap.
        assertEquals(mid * threePages.size + 2, near)

        val nearEnd = recentreIfDrifting(
            (HUD_PAGER_LAPS - 1) * threePages.size + 1, threePages, endless = true,
        )
        assertNotNull(nearEnd)
        assertEquals(mid * threePages.size + 1, nearEnd)
    }

    @Test
    fun recentreIfDriftingReturnsNullWhenEndlessOff() {
        assertNull(recentreIfDrifting(0, threePages, endless = false))
    }
}
