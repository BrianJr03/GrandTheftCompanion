package jr.brian.thorhud.ui.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the shared cheats catalog. The ids and the
 * (one-shot vs toggle) split must match asi/src/cheats.h; this is the
 * app-side half of that contract.
 */
class CheatCatalogTest {

    private fun idsFor(isGtaV: Boolean): Set<Int> =
        visibleCheats(isGtaV).values.flatten().map { it.id }.toSet()

    @Test
    fun idsSpanTheExpectedRanges() {
        for (def in CHEATS) {
            if (def.isToggle) {
                assertTrue("toggle id ${def.id} must be >= $FIRST_TOGGLE_ID",
                           def.id >= FIRST_TOGGLE_ID)
                // kMaxToggle in asi-v/src/cheats.h is 34; app allows
                // ids up to 51 (bit 31 of the Int mask) but the
                // plugin's enum stops at 34 today.
                assertTrue("toggle id ${def.id} must be <= 34",
                           def.id <= 34)
            } else {
                // Two one-shot bands around the toggle range: 1..19 and
                // 40..41 (IV weapon sets). The IV spawn range (50..58)
                // is reserved in the plugin but no catalog entries live
                // there yet — the on-device test showed REQUEST_MODEL
                // from the per-frame hook crashes IV.
                assertTrue("one-shot id ${def.id} must be in 1..19 or 40..41",
                           def.id in 1..19 || def.id in 40..41)
            }
        }
    }

    @Test
    fun ivHidesVOnlyEntries() {
        val iv = idsFor(isGtaV = false)
        // Shared cheats that must be visible on IV.
        assertTrue(iv.containsAll(setOf(1, 2, 3, 4, 5, 6, 7, 8, 17, 20, 22, 23, 26, 27)))
        // IV-only phone-cheat weapon sets + ultimate.
        assertTrue(iv.containsAll(setOf(19, 40, 41)))
        // V-only must not leak into IV.
        for (id in listOf(9, 10, 11, 12, 13, 14, 15, 16, 18, 21, 24, 25,
                          28, 29, 30, 31, 32, 33, 34)) {
            assertFalse("V-only id $id leaked onto IV", iv.contains(id))
        }
        // IV spawn range (50..58) is reserved but pulled from UI.
        for (id in 50..58) {
            assertFalse("IV spawn id $id must not appear in the catalog yet",
                        iv.contains(id))
        }
    }

    @Test
    fun vSeesEveryVEntry() {
        val v = idsFor(isGtaV = true)
        for (id in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9,
                          10, 11, 12, 13, 14, 15,
                          16, 17, 18,
                          20, 21, 24, 25, 26, 27,
                          28, 29, 30, 31, 32, 33, 34)) {
            assertTrue("V missing id $id", v.contains(id))
        }
        // IV-only entries must not appear on V (fast reload, never
        // tired, IV phone-cheat weapon sets + ultimate).
        for (id in listOf(22, 23, 19, 40, 41)) {
            assertFalse("IV-only id $id leaked onto V", v.contains(id))
        }
    }

    @Test
    fun isOnReadsTheRightBit() {
        // Toggle id N lights bit (N - FIRST_TOGGLE_ID).
        val mask = 0b0000_0101   // bits 0 and 2 → ids 20 and 22
        assertTrue(isOn(mask, 20))
        assertFalse(isOn(mask, 21))
        assertTrue(isOn(mask, 22))
        assertFalse(isOn(mask, 26))
        // Null mask reads as all-off.
        assertFalse(isOn(null, 20))
        // One-shot ids never light.
        assertFalse(isOn(0xFFFFFFFF.toInt(), 1))
        assertFalse(isOn(0xFFFFFFFF.toInt(), 19))
    }

    @Test
    fun catalogHasEveryExpectedId() {
        val ids = CHEATS.map { it.id }.toSet()
        for (id in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9,
                          10, 11, 12, 13, 14, 15,
                          16, 17, 18, 19,
                          20, 21, 22, 23, 24, 25, 26, 27,
                          28, 29, 30, 31, 32, 33, 34,
                          40, 41)) {
            assertTrue("missing cheat id $id in catalog", ids.contains(id))
        }
        assertEquals("one id per cheat", CHEATS.size, ids.size)
    }

    @Test
    fun groupedByCategoryHasOrderedSections() {
        // visibleCheats returns a LinkedHashMap keyed by category in
        // the enum's declaration order; CheatsScreen walks that order
        // to render section headers. A regression here would scramble
        // the sections on the companion.
        val onV = visibleCheats(isGtaV = true)
        assertEquals(
            listOf(CheatCategory.Health, CheatCategory.Wanted,
                   CheatCategory.Weapons, CheatCategory.Vehicle,
                   CheatCategory.World, CheatCategory.Toggles),
            onV.keys.toList(),
        )
        // Wanted trio must be clustered inside the Wanted section with
        // clear, +1, -1 all present.
        assertEquals(
            setOf(2, 3, 16),
            onV[CheatCategory.Wanted].orEmpty().map { it.id }.toSet(),
        )
    }
}
