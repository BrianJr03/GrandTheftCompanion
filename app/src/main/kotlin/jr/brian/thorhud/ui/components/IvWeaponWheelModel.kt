package jr.brian.thorhud.ui.components

import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.v.VWheelSegment
import jr.brian.thorhud.ui.v.VWheelSlot
import jr.brian.thorhud.ui.v.VWheelWeapon
import jr.brian.thorhud.ui.v.wheelSegments

// --- GTA IV weapon wheel: model -----------------------------------------
//
// GTA IV has no weapon wheel; this lays its loadout out on the one the
// companion draws for GTA V (ui/v/VWeaponWheel.kt), reusing that wheel's
// slots, segments and tap rules. No Compose here, so it is covered by a
// plain JVM test (IvWeaponWheelModelTest).
//
// IV is simpler than V: the player carries at most one weapon per game
// slot, and the plugin reports that slot with each weapon (`owned` in
// the state frame, plugin natives level 4), so there is no group table.

/** GTA IV weapon id for fists. */
internal const val IV_WEAPON_UNARMED = 0

// Scripting eWeaponSlot values the plugin reports (asi/src/weapons.h
// scans slots 1..9).
private const val SLOT_MELEE = 1
private const val SLOT_HANDGUN = 2
private const val SLOT_SHOTGUN = 3
private const val SLOT_SMG = 4
private const val SLOT_RIFLE = 5
private const val SLOT_SNIPER = 6
private const val SLOT_HEAVY = 7
private const val SLOT_THROWN = 8
private const val SLOT_SPECIAL = 9

/**
 * Wheel slot for a GTA IV game slot, or null for slots that are not
 * weapons to pick (0 fists, which the wheel adds itself; 10 gift; 11
 * parachute; anything unknown).
 *
 * "Special" shares the throwables slot, as GTA V's wheel does, so the
 * few weapons that live there stay reachable.
 */
internal fun ivWheelSlotOf(gameSlot: Int): VWheelSlot? = when (gameSlot) {
    SLOT_MELEE   -> VWheelSlot.Melee
    SLOT_HANDGUN -> VWheelSlot.Pistols
    SLOT_SHOTGUN -> VWheelSlot.Shotguns
    SLOT_SMG     -> VWheelSlot.Smgs
    SLOT_RIFLE   -> VWheelSlot.Rifles
    SLOT_SNIPER  -> VWheelSlot.Snipers
    SLOT_HEAVY   -> VWheelSlot.Heavy
    SLOT_THROWN, SLOT_SPECIAL -> VWheelSlot.Thrown
    else         -> null
}

private fun ivWheelWeapon(id: Int, ammo: Int) =
    VWheelWeapon(hash = id.toLong(), name = "", group = 0, ammo = ammo)

/**
 * Lay the carried weapons out on the wheel.
 *
 * Fists are always first in the melee slot, so that slot steps between
 * fists and the melee weapon when tapped twice; throwables and special
 * can share a slot the same way. Every other slot holds one weapon.
 *
 * @param owned `owned` from the state frame
 * @param selectedId the weapon in hand (`weapon`), or a pick waiting
 *                   for the game to confirm it. Null means fists.
 * @return eight segments, indexed by [VWheelSlot.ordinal]; each
 *         weapon's `hash` is its GTA IV weapon id
 */
internal fun ivBuildWheel(
    owned: List<GameState.OwnedWeapon>,
    selectedId: Int?,
    order: List<VWheelSlot> = VWheelSlot.entries,
): List<VWheelSegment> {
    val bySlot = VWheelSlot.entries.associateWith { mutableListOf<VWheelWeapon>() }
    bySlot.getValue(VWheelSlot.Melee).add(ivWheelWeapon(IV_WEAPON_UNARMED, 0))
    // By game slot, so a shared wheel slot lists its weapons in a
    // stable order whatever order the plugin sent them in.
    for (weapon in owned.sortedBy { it.slot }) {
        if (weapon.id <= IV_WEAPON_UNARMED) continue
        val slot = ivWheelSlotOf(weapon.slot) ?: continue
        bySlot.getValue(slot).add(ivWheelWeapon(weapon.id, weapon.ammo))
    }
    return wheelSegments(
        bySlot = bySlot,
        selected = (selectedId ?: IV_WEAPON_UNARMED).toLong(),
        remembered = emptyMap(),
        order = order,
    )
}
