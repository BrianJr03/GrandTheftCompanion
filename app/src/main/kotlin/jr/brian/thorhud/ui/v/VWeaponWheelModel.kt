package jr.brian.thorhud.ui.v

import kotlin.math.atan2
import kotlin.math.hypot

// --- GTA V weapon wheel: model ------------------------------------------
//
// Everything about the wheel that is not drawing lives here, free of
// Compose and Android, so it is covered by plain JVM unit tests
// (VWeaponWheelModelTest). VWeaponWheel.kt draws what this file decides.
//
// The wheel mirrors the game's: eight slots, each holding every owned
// weapon of one kind and showing one of them at a time.

/**
 * The eight slots, in the order the game is understood to number them
 * (WHEEL_PISTOL = 0 .. WHEEL_THROWABLE_SPECIAL = 7). Slot n is drawn
 * centred n * 45° clockwise from three o'clock, which should be where
 * the game puts it: pistols right, rifles down, melee left, heavy up.
 * Not yet compared with the real wheel on a device. If a slot sits
 * somewhere else there, reorder these entries; nothing else depends on
 * the order.
 *
 * [label] is English and lower-case, like the weapon names in
 * VWeapons.kt.
 */
internal enum class VWheelSlot(val label: String) {
    Pistols("pistols"),
    Smgs("SMGs"),
    Rifles("rifles"),
    Snipers("snipers"),
    Melee("melee"),
    Shotguns("shotguns"),
    Heavy("heavy"),
    Thrown("thrown"),
}

/** One owned weapon, as the `weapons` frame describes it. */
internal data class VWheelWeapon(
    val hash: Long,
    val name: String,
    val group: Long,
    val ammo: Int,
)

/**
 * One slot of the wheel.
 *
 * @param weapons every owned weapon in this slot, in the plugin's order
 * @param shownIndex which of [weapons] the slot displays, -1 when empty
 * @param isSelected true when the displayed weapon is the selected one
 *                   (equipped, or picked and waiting to be equipped)
 */
internal data class VWheelSegment(
    val slot: VWheelSlot,
    val weapons: List<VWheelWeapon>,
    val shownIndex: Int,
    val isSelected: Boolean,
) {
    val shown: VWheelWeapon? get() = weapons.getOrNull(shownIndex)
}

/**
 * Jenkins one-at-a-time over the lower-cased name: the hash GTA V uses
 * for weapon and group names. Same function as asi-v/src/joaat.h,
 * returned as an unsigned value in a Long like every hash in the app.
 */
internal fun vJoaat(text: String): Long {
    var h = 0u
    for (c in text.lowercase()) {
        h += c.code.toUInt()
        h += h shl 10
        h = h xor (h shr 6)
    }
    h += h shl 3
    h = h xor (h shr 11)
    h += h shl 15
    return h.toLong()
}

// GET_WEAPONTYPE_GROUP values (the `group` field of the weapons frame)
// and the slot each one goes in. Machine guns share the SMG slot, the
// stun gun sits with the pistols, and the jerry can and fire
// extinguisher with the throwables. The game's wheel has its own
// per-weapon slot data that scripts cannot read, so a weapon whose
// group disagrees with it will show in the wrong slot here; correct
// those by name, ahead of this table, as they are found on a device.
private val SLOT_BY_GROUP: Map<Long, VWheelSlot> = mapOf(
    vJoaat("GROUP_PISTOL") to VWheelSlot.Pistols,
    vJoaat("GROUP_STUNGUN") to VWheelSlot.Pistols,
    vJoaat("GROUP_SMG") to VWheelSlot.Smgs,
    vJoaat("GROUP_MG") to VWheelSlot.Smgs,
    vJoaat("GROUP_RIFLE") to VWheelSlot.Rifles,
    vJoaat("GROUP_SNIPER") to VWheelSlot.Snipers,
    vJoaat("GROUP_MELEE") to VWheelSlot.Melee,
    vJoaat("GROUP_UNARMED") to VWheelSlot.Melee,
    vJoaat("GROUP_SHOTGUN") to VWheelSlot.Shotguns,
    vJoaat("GROUP_HEAVY") to VWheelSlot.Heavy,
    vJoaat("GROUP_THROWN") to VWheelSlot.Thrown,
    vJoaat("GROUP_PETROLCAN") to VWheelSlot.Thrown,
    vJoaat("GROUP_FIREEXTINGUISHER") to VWheelSlot.Thrown,
)

/** Fists. Never in the plugin's owned list, always on the wheel. */
private val UNARMED = VWheelWeapon(
    hash = V_WEAPON_UNARMED_HASH,
    name = "weapon_unarmed",
    group = vJoaat("GROUP_UNARMED"),
    ammo = 0,
)

/**
 * Slot for a weapon, or null if it does not go on the wheel.
 *
 * Gadgets (the parachute) are owned but not selectable. A group this
 * table does not know lands in the throwables slot, which is also the
 * game's "special" slot, so an owned weapon is never unreachable.
 */
internal fun vWheelSlotOf(name: String, group: Long): VWheelSlot? {
    if (name.startsWith("gadget_")) return null
    return SLOT_BY_GROUP[group] ?: VWheelSlot.Thrown
}

/** Slot holding the weapon with this hash, or null if none does. */
internal fun vWheelSlotOfHash(owned: List<VWheelWeapon>, hash: Long?): VWheelSlot? {
    if (hash == null || hash == 0L) return null
    if (hash == V_WEAPON_UNARMED_HASH) return VWheelSlot.Melee
    val weapon = owned.firstOrNull { it.hash == hash } ?: return null
    return vWheelSlotOf(weapon.name, weapon.group)
}

/**
 * Lay the owned weapons out on the wheel.
 *
 * Each slot displays, in order of preference: the selected weapon if it
 * is in the slot; the weapon last used from the slot ([remembered]), if
 * still owned; the slot's first weapon.
 *
 * @param selectedHash the equipped weapon, or a pick waiting to be
 *                     equipped. Null / 0 / the unarmed hash all mean
 *                     fists.
 * @return eight segments, indexed by [VWheelSlot.ordinal]
 */
internal fun vBuildWheel(
    owned: List<VWheelWeapon>,
    selectedHash: Long?,
    remembered: Map<VWheelSlot, Long>,
): List<VWheelSegment> {
    val bySlot = VWheelSlot.entries.associateWith { mutableListOf<VWheelWeapon>() }
    bySlot.getValue(VWheelSlot.Melee).add(UNARMED)
    for (weapon in owned) {
        if (weapon.hash == V_WEAPON_UNARMED_HASH) continue
        val slot = vWheelSlotOf(weapon.name, weapon.group) ?: continue
        bySlot.getValue(slot).add(weapon)
    }
    val selected = if (vIsUnarmed(null, selectedHash)) V_WEAPON_UNARMED_HASH
                   else selectedHash
    return VWheelSlot.entries.map { slot ->
        val weapons = bySlot.getValue(slot)
        val selectedIndex = weapons.indexOfFirst { it.hash == selected }
        val rememberedIndex = weapons.indexOfFirst { it.hash == remembered[slot] }
        val shownIndex = when {
            selectedIndex >= 0   -> selectedIndex
            rememberedIndex >= 0 -> rememberedIndex
            weapons.isNotEmpty() -> 0
            else                 -> -1
        }
        VWheelSegment(slot, weapons, shownIndex, isSelected = selectedIndex >= 0)
    }
}

/**
 * The weapon a tap on [segment] asks for, or null if the tap does
 * nothing.
 *
 * A slot that does not hold the selected weapon gives the weapon it is
 * showing. Tapping the slot that already holds the selected weapon steps
 * to the next weapon in it, wrapping around: the touch version of the
 * game's left/right within a slot.
 */
internal fun vWheelTapTarget(segment: VWheelSegment): Long? {
    val shown = segment.shown ?: return null
    if (!segment.isSelected) return shown.hash
    if (segment.weapons.size < 2) return null
    return segment.weapons[(segment.shownIndex + 1) % segment.weapons.size].hash
}

// --- geometry, shared by drawing and hit-testing ------------------------

/** Wheel radius as a fraction of half the shorter side of its box. */
internal const val V_WHEEL_OUTER_FRACTION = 0.96f

/** Radius of the hub (the hole) as a fraction of the wheel radius. */
internal const val V_WHEEL_INNER_FRACTION = 0.40f

/** Angle each slot spans. */
internal const val V_WHEEL_SWEEP_DEG = 360f / 8

/**
 * Index of the slot under a touch, or null for the hub and for anything
 * outside the wheel.
 *
 * @param dx, dy touch position relative to the wheel's centre, y down
 * @param outerRadius the wheel's radius in the same units
 */
internal fun vWheelSegmentAt(dx: Float, dy: Float, outerRadius: Float): Int? {
    val r = hypot(dx, dy)
    if (r > outerRadius || r < outerRadius * V_WHEEL_INNER_FRACTION) return null
    // With y pointing down, atan2 already runs clockwise from three
    // o'clock, the way the slots are numbered. Shift by half a slot so
    // slot 0 covers -22.5°..22.5°.
    val degrees = Math.toDegrees(atan2(dy, dx).toDouble())
    val shifted = (degrees + 360.0 + V_WHEEL_SWEEP_DEG / 2) % 360.0
    return (shifted / V_WHEEL_SWEEP_DEG).toInt().coerceIn(0, VWheelSlot.entries.lastIndex)
}
