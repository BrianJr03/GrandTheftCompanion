package jr.brian.thorhud.ui.customs

import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Mods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure-Kotlin shaping used by the Customs screen: pack/unpack
 * of the set_mod wire value, slot-to-section grouping, plate text
 * validation. On-device decisions (which slot is "spoiler", etc.) are
 * out of scope here — those come from the game at runtime.
 */
class CustomsModelTest {

    private fun slot(slot: Int, cur: Int = -1, nOptions: Int = 2): GameState.ModSlot =
        GameState.ModSlot(
            slot = slot,
            cur = cur,
            name = "slot $slot",
            options = List(nOptions) { "option $it" },
        )

    private fun toggle(slot: Int, on: Boolean = false): GameState.ModToggle =
        GameState.ModToggle(slot = slot, on = on, name = "toggle $slot")

    private fun mods(
        slots: List<GameState.ModSlot> = emptyList(),
        toggles: List<GameState.ModToggle> = emptyList(),
        kit: Boolean = true,
    ): Mods = Mods(
        ver = 1, vehicleModel = 0, kit = kit,
        wheelType = 0, tints = 6, tint = 0,
        plateStyle = 0, plate = "",
        neon = false, bulletproof = false,
        slots = slots, toggles = toggles,
    )

    @Test
    fun packModPacksSlotIntoHighWordAndIndexIntoLow() {
        assertEquals(((11L shl 16) or 2L), packMod(11, 2))
        assertEquals(((0L shl 16) or 1L), packMod(0, 1))
    }

    @Test
    fun packModMapsStockToWireSentinel() {
        // STOCK = -1 on the UI side; 0xFFFF on the wire.
        assertEquals(((0L shl 16) or 0xFFFFL), packMod(0, STOCK))
        assertEquals(((23L shl 16) or 0xFFFFL), packMod(23, STOCK))
    }

    @Test
    fun customsRowsGroupsBySection() {
        val m = mods(
            slots = listOf(
                slot(0),   // spoiler  -> Body
                slot(11),  // engine   -> Performance
                slot(23),  // wheels   -> Wheels
                slot(25),  // plate holder -> Plate
                slot(29),  // dashboard -> Interior
            ),
            toggles = listOf(toggle(18), toggle(22)),
        )
        val rows = customsRows(m)
        val sections = rows.sections.map { it.first }
        // Sections preserve the enum order; empty ones are dropped.
        assertEquals(
            listOf(
                CustomsSection.Body,
                CustomsSection.Performance,
                CustomsSection.Wheels,
                CustomsSection.Interior,
                CustomsSection.LightsExtras,
                CustomsSection.Plate,
            ),
            sections,
        )
        val lights = rows.sections.first { it.first == CustomsSection.LightsExtras }.second
        assertEquals(
            "toggles 18 and 22 ride in LightsExtras",
            listOf(18, 22),
            lights.filterIsInstance<CustomsRow.ToggleRow>().map { it.toggle.slot },
        )
    }

    @Test
    fun customsRowsPreservesInputOrderInsideSection() {
        val m = mods(slots = listOf(slot(11), slot(12), slot(15)))
        val rows = customsRows(m)
        val perf = rows.sections.first { it.first == CustomsSection.Performance }.second
        assertEquals(
            listOf(11, 12, 15),
            perf.filterIsInstance<CustomsRow.SlotRow>().map { it.slot.slot },
        )
    }

    @Test
    fun unknownSlotLandsInOther() {
        val m = mods(slots = listOf(slot(99)))
        val rows = customsRows(m)
        assertEquals(
            listOf(CustomsSection.Other),
            rows.sections.map { it.first },
        )
    }

    @Test
    fun plateValidatorAcceptsEightUppercaseAlnumOrSpace() {
        assertTrue(isValidPlateText("GTC 123"))
        assertTrue(isValidPlateText("A"))
        assertTrue(isValidPlateText("12345678"))
        assertTrue(isValidPlateText("ABC DEFG"))
    }

    @Test
    fun plateValidatorRejectsEmpty() {
        assertFalse(isValidPlateText(""))
    }

    @Test
    fun plateValidatorRejectsOverEight() {
        assertFalse(isValidPlateText("ABCDEFGHI"))
    }

    @Test
    fun plateValidatorRejectsPunctuationAndLower() {
        assertFalse(isValidPlateText("hi"))
        assertFalse(isValidPlateText("A!"))
        assertFalse(isValidPlateText("GTC-123"))
    }
}
