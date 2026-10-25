package jr.brian.thorhud.ui.ammunation

import jr.brian.thorhud.model.ArmoryGroup
import jr.brian.thorhud.model.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the Ammu-Nation sheet's picker + fallback
 * group names. The ◀ ▶ cycle must always start and end on stock, and
 * each hop must land on the right (cidx, on) command so the plugin
 * fits/removes the correct component.
 */
class AmmuNationModelTest {

    private fun opt(
        cidx: Int,
        bone: Long = 1L,
        default: Boolean = false,
        fitted: Boolean = false,
        name: String = "opt$cidx",
    ) = GameState.ArmoryOption(cidx, bone, default, fitted, name)

    private fun stockGroup(nAlternatives: Int, curCidx: Int = -1): ArmoryGroup {
        val options = buildList {
            add(opt(0, default = true, name = "Default"))
            for (i in 1..nAlternatives) add(opt(i, name = "Option $i"))
        }
        return ArmoryGroup(bone = 1L, curCidx = curCidx, options = options)
    }

    @Test
    fun stockAdvancesToFirstAlternative() {
        val g = stockGroup(3)                     // cur = -1 (stock)
        val cmd = cycleForward(g)
        assertEquals(CycleCommand(cidx = 1, on = true), cmd)
    }

    @Test
    fun firstAlternativeAdvancesToSecond() {
        val g = stockGroup(3, curCidx = 1)
        assertEquals(CycleCommand(cidx = 2, on = true), cycleForward(g))
    }

    @Test
    fun lastAlternativeAdvancesToStockViaRemoval() {
        val g = stockGroup(3, curCidx = 3)        // last non-default fitted
        assertEquals(CycleCommand(cidx = 3, on = false), cycleForward(g))
    }

    @Test
    fun backwardFromStockLandsOnLastAlternative() {
        val g = stockGroup(3)
        assertEquals(CycleCommand(cidx = 3, on = true), cycleBack(g))
    }

    @Test
    fun backwardFromFirstAlternativeLandsOnStockViaRemoval() {
        val g = stockGroup(3, curCidx = 1)
        assertEquals(CycleCommand(cidx = 1, on = false), cycleBack(g))
    }

    @Test
    fun groupWithOnlyDefaultHasNoCommand() {
        val onlyDefault = ArmoryGroup(
            bone = 1L, curCidx = -1,
            options = listOf(opt(0, default = true, name = "Default")),
        )
        assertNull(cycleForward(onlyDefault))
        assertNull(cycleBack(onlyDefault))
        assertEquals(0, selectableCount(onlyDefault))
    }

    @Test
    fun selectableCountCountsNonDefaultsOnly() {
        assertEquals(3, selectableCount(stockGroup(3)))
        assertEquals(0, selectableCount(
            ArmoryGroup(bone = 1L, curCidx = -1, options = emptyList())
        ))
    }

    @Test
    fun groupHeaderPrefersKnownBoneName() {
        // 3723347892 is the clip bone id (seen in Gate A0 on every
        // weapon with a Default Clip + Extended Clip pair).
        val clipGroup = ArmoryGroup(
            bone = 3723347892L, curCidx = -1,
            options = listOf(opt(0, default = true, name = "d")),
        )
        assertEquals("Clip", groupHeader(clipGroup, index = 0))
    }

    @Test
    fun groupHeaderFallsBackToIndexedAttachment() {
        val unknown = ArmoryGroup(
            bone = 1234567890L, curCidx = -1,
            options = listOf(opt(0, default = true)),
        )
        assertEquals("Attachment 1", groupHeader(unknown, index = 0))
        assertEquals("Attachment 4", groupHeader(unknown, index = 3))
    }

    @Test
    fun currentOptionReturnsFittedNonDefaultOrNull() {
        assertNull(currentOption(stockGroup(2)))
        val fitted = ArmoryGroup(
            bone = 1L, curCidx = 2,
            options = listOf(
                opt(0, default = true, name = "d"),
                opt(1, name = "a"),
                opt(2, fitted = true, name = "b"),
            ),
        )
        assertEquals("b", currentOption(fitted)!!.name)
    }

    @Test
    fun clampedAddDoesNotExceedCap() {
        assertEquals(50, clampedAdd(current = 48, clipSize = 12, maxAmmo = 50))
        assertEquals(60, clampedAdd(current = 48, clipSize = 12, maxAmmo = 100))
        assertEquals(0,  clampedAdd(current = 0,  clipSize = 0,  maxAmmo = 10))
    }

    @Test
    fun kBoneSlotNamesCoversEveryObservedBone() {
        // Pin the probe-captured bones (Gate A0) + the Gate A1 sweep
        // additions so a rename of this map is a deliberate change,
        // not an accidental one. Add new bones here as new weapons /
        // DLCs land.
        val expected = setOf(
            // Gate A0
            3723347892L, 679107254L, 945598191L, 1863181664L,
            1684637069L, 196630833L, 2972950469L, 962500902L,
            // Gate A1 (Combat PDW, Bullpup Rifle, Heavy Shotgun,
            // Marksman Rifle on Franklin's save)
            291640902L, 2722126698L, 3748215485L,
        )
        assertTrue(
            "missing bones: ${expected - kBoneSlotNames.keys}",
            expected.all { it in kBoneSlotNames },
        )
        assertFalse(kBoneSlotNames.containsKey(0L))
    }
}
