package jr.brian.thorhud.ui.wardrobe

import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Wardrobe

/**
 * Pure helpers for the Wardrobe sheet: piece packing, row grouping,
 * outfit snapshotting, preset (de)serialisation.
 *
 * No Android or Compose imports here — every function is driven off
 * [Wardrobe] alone so [WardrobeModelTest] can pin behaviour against
 * captured frames without a device.
 *
 * The piece packing matches the plugin's unpack_piece in
 * asi-v/src/wardrobe.cpp: bit 24 = prop, bits 20-23 slot, bits 8-19
 * drawable (0xFFF on a prop = clear), bits 0-7 texture. Any value
 * outside those field widths is silently masked.
 */

/** The "nothing fitted" sentinel on a prop drawable. Plugin side
 *  calls CLEAR_PED_PROP for this value. Only valid on prop pieces;
 *  a component piece with drawable < 0 is a programmer error and
 *  [packPiece] clamps it to 0 to keep the wire line parseable. */
internal const val PROP_CLEAR = -1

/** Largest preset count stored per character. Picked so a worst-case
 *  prefs line still fits in a few KB; the screen hides the "save"
 *  button when the user hits this. */
internal const val MAX_PRESETS = 20

/** Largest outfit the plugin's apply_outfit will accept; matches
 *  asi-v/src/commands.h kMaxParts and the app's MAX_PARTS. */
internal const val MAX_OUTFIT_PARTS = 24

private const val BIT_PROP   = 1 shl 24
private const val SLOT_MASK  = 0xF
private const val SLOT_SHIFT = 20
private const val DRAW_MASK  = 0xFFF
private const val DRAW_SHIFT = 8
private const val TEX_MASK   = 0xFF
private const val PROP_CLEAR_WIRE = 0xFFF

/**
 * Pack a (prop, slot, drawable, texture) tuple into one u32 the
 * plugin's wardrobe::apply reads. [drawable] = [PROP_CLEAR] on a prop
 * maps to the wire sentinel 0xFFF; on a component it clamps to 0 so
 * the frame never carries a nonsense negative drawable.
 */
internal fun packPiece(
    isProp: Boolean, slot: Int, drawable: Int, texture: Int,
): Long {
    val draw = when {
        isProp && drawable == PROP_CLEAR -> PROP_CLEAR_WIRE
        drawable < 0                     -> 0
        else                             -> drawable and DRAW_MASK
    }
    val bit = if (isProp) BIT_PROP else 0
    val s = (slot and SLOT_MASK) shl SLOT_SHIFT
    val d = (draw and DRAW_MASK) shl DRAW_SHIFT
    val t = texture and TEX_MASK
    return (bit or s or d or t).toLong() and 0xFFFFFFFFL
}

/** Mirror of [packPiece], used by tests and preset round-trip. */
internal data class UnpackedPiece(
    val isProp: Boolean,
    val slot: Int,
    val drawable: Int,
    val texture: Int,
)

internal fun unpackPiece(v: Long): UnpackedPiece {
    val isProp = ((v shr 24) and 1L) != 0L
    val slot = ((v shr SLOT_SHIFT) and SLOT_MASK.toLong()).toInt()
    val rawDraw = ((v shr DRAW_SHIFT) and DRAW_MASK.toLong()).toInt()
    val drawable = if (isProp && rawDraw == PROP_CLEAR_WIRE) PROP_CLEAR else rawDraw
    val texture = (v and TEX_MASK.toLong()).toInt()
    return UnpackedPiece(isProp, slot, drawable, texture)
}

/**
 * The display-time grouping of slot rows on the Wardrobe screen.
 * Mirrors the Customs rows pattern: a flat section → row list the
 * screen can lay out directly. Rows arrive in the group order below
 * and in ascending (slot, prop) order inside each group.
 *
 * Section membership is a soft prediction (slot ids on SP
 * protagonists); a slot that doesn't match any group lands in [Other]
 * so a wrong guess costs a mislabeled row, never a crash.
 */
internal enum class WardrobeSection {
    Tops,            // 3 (torso), 8 (undershirt), 11 (tops)
    Legs,            // 4 (legs)
    Feet,            // 6 (feet)
    Hair,            // 2 (hair), 1 (mask / beard)
    Accessories,     // 5 (hands/bag), 7 (accessories), 9 (armour/task)
    Props,           // every prop row
    Other,           // anything not mapped
}

internal data class WardrobeRow(
    val slot: GameState.WardrobeSlot,
    val isProp: Boolean = slot.prop,
)

internal data class WardrobeRows(
    val sections: List<Pair<WardrobeSection, List<WardrobeRow>>>,
)

/**
 * Group every piece in [wardrobe] under section headers. Sections
 * with no rows are filtered out so the UI never renders an empty
 * header. The screen reads the sections in the order the enum
 * declares; this file never sorts beyond that.
 */
internal fun wardrobeRows(wardrobe: Wardrobe): WardrobeRows {
    val bySection = linkedMapOf<WardrobeSection, MutableList<WardrobeRow>>()
    for (section in WardrobeSection.entries) bySection[section] = mutableListOf()
    for (slot in wardrobe.pieces) {
        val section = if (slot.prop) WardrobeSection.Props
                      else componentSection(slot.slot)
        bySection.getValue(section).add(WardrobeRow(slot))
    }
    val sections = bySection.entries
        .filter { it.value.isNotEmpty() }
        .map { (section, rows) -> section to rows.toList() }
    return WardrobeRows(sections)
}

/** Section a component slot belongs to. Numbers follow the NativeDB
 *  component enum; the mapping is deliberately conservative so a
 *  wrong guess parks the slot in [WardrobeSection.Other] rather than
 *  mislabelling. */
internal fun componentSection(slot: Int): WardrobeSection = when (slot) {
    3, 8, 11 -> WardrobeSection.Tops
    4        -> WardrobeSection.Legs
    6        -> WardrobeSection.Feet
    1, 2     -> WardrobeSection.Hair
    5, 7, 9  -> WardrobeSection.Accessories
    else     -> WardrobeSection.Other
}

/**
 * Snapshot the current ped's full look as a list of packed pieces,
 * ready for [applyOutfit] on the way back. Prop slots whose current
 * drawable is -1 are included as a clear so a saved "no hat" look
 * actually takes the hat off on re-apply.
 *
 * Capped at [MAX_OUTFIT_PARTS]; the plugin refuses longer arrays and
 * the sheet hides save on bigger inputs than that. SP protagonists
 * stay well under the cap.
 */
internal fun snapshotOutfit(wardrobe: Wardrobe): List<Long> =
    wardrobe.pieces.take(MAX_OUTFIT_PARTS).map { s ->
        packPiece(s.prop, s.slot, s.cur, s.tex)
    }

/**
 * One saved look on the phone. Presets are keyed by `(game, ped
 * model)` outside this file (see HudPrefs.wardrobePresets); the
 * [name] is user-chosen and may be empty on a freshly-created preset.
 */
internal data class WardrobePreset(
    val name: String,
    val parts: List<Long>,
)

/** JSON-serialise a preset list for persistence. Pure Kotlin string
 *  concat so no kotlinx dependency on the model side. */
internal fun serialisePresets(presets: List<WardrobePreset>): String {
    val arr = presets.joinToString(",") { p ->
        val name = p.name.replace("\\", "\\\\").replace("\"", "\\\"")
        val parts = p.parts.joinToString(",")
        "{\"n\":\"$name\",\"p\":[$parts]}"
    }
    return "[$arr]"
}

/** Deserialise a preset list from [serialisePresets]. Returns an
 *  empty list on empty / malformed input; this is prefs state, so
 *  loudness isn't useful — the screen just forgets a corrupted bag. */
internal fun deserialisePresets(raw: String?): List<WardrobePreset> {
    if (raw.isNullOrBlank()) return emptyList()
    val out = mutableListOf<WardrobePreset>()
    var i = 0
    val s = raw.trim()
    if (s.isEmpty() || s.first() != '[') return emptyList()
    i = 1
    while (i < s.length && s[i] != ']') {
        while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++
        if (i >= s.length || s[i] == ']') break
        if (s[i] != '{') return emptyList()
        val end = findObjectEnd(s, i) ?: return emptyList()
        val obj = s.substring(i, end + 1)
        val name = readStringField(obj, "n") ?: return emptyList()
        val partsRaw = readArrayField(obj, "p") ?: return emptyList()
        val parts = partsRaw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull() }
        if (parts.size > MAX_OUTFIT_PARTS) return emptyList()
        out.add(WardrobePreset(name, parts))
        i = end + 1
        if (out.size > MAX_PRESETS) return emptyList()
    }
    return out
}

/**
 * Compute the "add preset" button's effective state. Full presets
 * (list already at [MAX_PRESETS]) hide the button; a wardrobe that
 * has not loaded disables it.
 */
internal fun canSavePreset(wardrobe: Wardrobe?, existing: List<WardrobePreset>): Boolean =
    wardrobe != null &&
        wardrobe.pieces.isNotEmpty() &&
        existing.size < MAX_PRESETS

// --- plain-text JSON helpers (public for test) -----------------------

/** Find the matching `}` for the `{` at [startAt] inside [s]. */
internal fun findObjectEnd(s: String, startAt: Int): Int? {
    if (startAt >= s.length || s[startAt] != '{') return null
    var depth = 0
    var i = startAt
    var inStr = false
    var esc = false
    while (i < s.length) {
        val c = s[i]
        if (inStr) {
            if (esc) { esc = false }
            else if (c == '\\') { esc = true }
            else if (c == '"') { inStr = false }
        } else {
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return null
}

/** Read a `"key":"value"` string field out of a tiny JSON object. */
internal fun readStringField(obj: String, key: String): String? {
    val needle = "\"$key\":\""
    val a = obj.indexOf(needle)
    if (a < 0) return null
    var i = a + needle.length
    val out = StringBuilder()
    while (i < obj.length && obj[i] != '"') {
        val c = obj[i]
        if (c == '\\' && i + 1 < obj.length) {
            val n = obj[i + 1]
            if (n == '"' || n == '\\') { out.append(n); i += 2; continue }
            return null
        }
        out.append(c)
        i++
    }
    if (i >= obj.length) return null
    return out.toString()
}

/** Read a `"key":[...]` array field (contents only, no brackets). */
internal fun readArrayField(obj: String, key: String): String? {
    val needle = "\"$key\":["
    val a = obj.indexOf(needle)
    if (a < 0) return null
    val start = a + needle.length
    val end = obj.indexOf(']', start)
    if (end < 0) return null
    return obj.substring(start, end)
}
