package jr.brian.thorhud.ui.customs

import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Mods

/**
 * Groups vehicle-mod slots into section headers the Customs screen
 * renders in order (Paint lives above these on the sheet; it reuses
 * the existing cheats-tab PaintSection).
 *
 * Pure function, no Android imports, so [customsRows] + [packMod]
 * stay unit-testable. The Compose layer takes this list and lays
 * out ◀ ▶ rows for every slot, Switches for the toggles, and the
 * four fixed-shape rows (wheel type, tint, plate, bulletproof) from
 * the top-level [Mods] fields.
 *
 * Slot numbers come from the NativeDB modType enum; see the comment
 * in the task spec for the full list. Any slot the walker publishes
 * that is not mapped here is dropped into [CustomsSection.Other]
 * (invisible to the UI by default — the next session on-device will
 * tell us whether that bucket ever has anything).
 */
internal enum class CustomsSection {
    Paint,          // handled above the generated sections (PaintSection)
    Body,           // 0..6, 10
    Performance,    // 11, 12, 13, 15, 16
    Wheels,         // 23, 24
    Interior,       // 27..36, 39..44
    LightsExtras,   // 7..9, 14, 17, 19, 21, 25, 26, 37, 38, 45, 46, 47, 48
    Plate,          // 25, 26 (plus the plate text + style rows outside the slot walk)
    Other,          // anything not mapped
}

/**
 * Pack a (slot, index) pair into the single u32 the plugin's set_mod
 * command accepts. Index -1 (stock) maps to 0xFFFF on the wire; the
 * plugin unpacks it back to -1 and calls REMOVE_VEHICLE_MOD.
 */
internal fun packMod(slot: Int, index: Int): Long {
    val idx = if (index < 0) 0xFFFF else (index and 0xFFFF)
    return ((slot.toLong() and 0xFFFF) shl 16) or idx.toLong()
}

/** The "nothing fitted" sentinel the plugin writes as 0xFFFF. */
internal const val STOCK = -1

/**
 * One row the Customs screen will render. Slot and toggle rows carry
 * the [Mods] data straight through; the four fixed rows (wheel type,
 * tint, plate text, plate style, bulletproof) are built from the
 * top-level [Mods] fields and have no [slot] value in the ModSlot
 * sense (they still live under section headers).
 */
internal data class CustomsRows(
    val sections: List<Pair<CustomsSection, List<CustomsRow>>>,
)

internal sealed interface CustomsRow {
    data class SlotRow(val slot: GameState.ModSlot) : CustomsRow
    data class ToggleRow(val toggle: GameState.ModToggle) : CustomsRow
}

/**
 * Group the slots and toggles in [mods] under section headers. The
 * section order is the walk order on the sheet. Sections with no
 * rows this vehicle are filtered out so the UI never renders an
 * empty header. Catalogue walker publishes slots and toggles in
 * ascending slot order; we preserve that inside each section.
 */
internal fun customsRows(mods: Mods): CustomsRows {
    val bySection = linkedMapOf<CustomsSection, MutableList<CustomsRow>>()
    for (section in CustomsSection.entries) bySection[section] = mutableListOf()
    for (slot in mods.slots) {
        bySection.getValue(slotSection(slot.slot)).add(CustomsRow.SlotRow(slot))
    }
    for (toggle in mods.toggles) {
        bySection.getValue(toggleSection(toggle.slot)).add(CustomsRow.ToggleRow(toggle))
    }
    val sections = bySection.entries
        .filter { it.value.isNotEmpty() }
        .map { (section, rows) -> section to rows.toList() }
    return CustomsRows(sections)
}

/** Section a slot id belongs to. Numbers follow the walker's modType
 *  enum; the mapping is deliberately conservative so a wrong guess
 *  parks the slot in [CustomsSection.Other] instead of mislabelling. */
internal fun slotSection(slot: Int): CustomsSection = when (slot) {
    0, 1, 2, 3, 4, 5, 6, 10 -> CustomsSection.Body
    7, 8, 9                 -> CustomsSection.Body            // hood, fenders
    11, 12, 13, 15, 16      -> CustomsSection.Performance
    23, 24                  -> CustomsSection.Wheels
    25, 26                  -> CustomsSection.Plate
    14, 17, 19, 21,
    37, 38, 45, 46, 47, 48  -> CustomsSection.LightsExtras
    27, 28, 29, 30, 31, 32,
    33, 34, 35, 36,
    39, 40, 41, 42, 43, 44  -> CustomsSection.Interior
    else                    -> CustomsSection.Other
}

/** Toggle slot id -> section header. The three toggles (18 turbo, 20
 *  tyre smoke, 22 xenon) all read as "lights / extras" to a player. */
internal fun toggleSection(slot: Int): CustomsSection = when (slot) {
    18, 20, 22 -> CustomsSection.LightsExtras
    else       -> CustomsSection.Other
}

/**
 * Plate validator mirroring asi-v/src/mods.cpp's rules so the UI
 * can grey the Apply button on malformed input instead of waiting
 * for the plugin's refusal. Rules: 1..8 chars, [A-Z0-9 ] only.
 */
internal fun isValidPlateText(text: String): Boolean {
    if (text.isEmpty() || text.length > 8) return false
    return text.all { c ->
        (c in 'A'..'Z') || (c in '0'..'9') || c == ' '
    }
}
