package jr.brian.thorhud.ui.components

import jr.brian.thorhud.model.GameState.OwnedWeapon
import jr.brian.thorhud.ui.v.VWheelSegment
import jr.brian.thorhud.ui.v.VWheelSlot
import jr.brian.thorhud.ui.v.vWheelTapTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IvWeaponWheelModelTest {

    // GTA IV weapon ids (res/values/strings.xml weapon_names).
    private val bat = 1
    private val grenade = 4
    private val pistol = 7
    private val pumpShotgun = 10
    private val microSmg = 12
    private val ak47 = 14
    private val sniper = 16
    private val rpg = 18

    // Niko mid-game, as the plugin reports it: game slot, id, ammo.
    private val loadout = listOf(
        OwnedWeapon(slot = 1, id = bat, ammo = 1),
        OwnedWeapon(slot = 2, id = pistol, ammo = 120),
        OwnedWeapon(slot = 3, id = pumpShotgun, ammo = 24),
        OwnedWeapon(slot = 4, id = microSmg, ammo = 300),
        OwnedWeapon(slot = 5, id = ak47, ammo = 240),
        OwnedWeapon(slot = 8, id = grenade, ammo = 5),
    )

    private fun List<VWheelSegment>.at(slot: VWheelSlot) = this[slot.ordinal]
    private fun VWheelSegment.ids() = weapons.map { it.hash.toInt() }

    // ---- which slot ----------------------------------------------------

    @Test
    fun gameSlotsMapToWheelSlots() {
        assertEquals(VWheelSlot.Melee, ivWheelSlotOf(1))
        assertEquals(VWheelSlot.Pistols, ivWheelSlotOf(2))
        assertEquals(VWheelSlot.Shotguns, ivWheelSlotOf(3))
        assertEquals(VWheelSlot.Smgs, ivWheelSlotOf(4))
        assertEquals(VWheelSlot.Rifles, ivWheelSlotOf(5))
        assertEquals(VWheelSlot.Snipers, ivWheelSlotOf(6))
        assertEquals(VWheelSlot.Heavy, ivWheelSlotOf(7))
        assertEquals(VWheelSlot.Thrown, ivWheelSlotOf(8))
        assertEquals(VWheelSlot.Thrown, ivWheelSlotOf(9))
    }

    @Test
    fun slotsThatAreNotPickableWeaponsAreLeftOut() {
        for (gameSlot in listOf(0, 10, 11, 12, -1, 99)) {
            assertNull("game slot $gameSlot", ivWheelSlotOf(gameSlot))
        }
    }

    // ---- layout --------------------------------------------------------

    @Test
    fun alwaysEightSegmentsInSlotOrder() {
        assertEquals(VWheelSlot.entries, ivBuildWheel(emptyList(), null).map { it.slot })
    }

    @Test
    fun noWeaponsStillOffersFists() {
        val wheel = ivBuildWheel(emptyList(), null)
        assertEquals(listOf(IV_WEAPON_UNARMED), wheel.at(VWheelSlot.Melee).ids())
        assertTrue(wheel.at(VWheelSlot.Melee).isSelected)
        assertEquals(1, wheel.count { it.weapons.isNotEmpty() })
    }

    @Test
    fun carriedWeaponsLandInTheirSlots() {
        val wheel = ivBuildWheel(loadout, pistol)
        assertEquals(listOf(IV_WEAPON_UNARMED, bat), wheel.at(VWheelSlot.Melee).ids())
        assertEquals(listOf(pistol), wheel.at(VWheelSlot.Pistols).ids())
        assertEquals(listOf(pumpShotgun), wheel.at(VWheelSlot.Shotguns).ids())
        assertEquals(listOf(microSmg), wheel.at(VWheelSlot.Smgs).ids())
        assertEquals(listOf(ak47), wheel.at(VWheelSlot.Rifles).ids())
        assertEquals(listOf(grenade), wheel.at(VWheelSlot.Thrown).ids())
        assertTrue(wheel.at(VWheelSlot.Snipers).weapons.isEmpty())
        assertTrue(wheel.at(VWheelSlot.Heavy).weapons.isEmpty())
    }

    @Test
    fun ammoIsCarriedThrough() {
        val wheel = ivBuildWheel(loadout, pistol)
        assertEquals(120, wheel.at(VWheelSlot.Pistols).shown?.ammo)
        assertEquals(5, wheel.at(VWheelSlot.Thrown).shown?.ammo)
    }

    @Test
    fun weaponInHandIsSelected() {
        val wheel = ivBuildWheel(loadout, ak47)
        assertTrue(wheel.at(VWheelSlot.Rifles).isSelected)
        assertEquals(1, wheel.count { it.isSelected })
    }

    @Test
    fun fistsAreSelectedWhenUnarmed() {
        for (id in listOf(null, IV_WEAPON_UNARMED)) {
            val wheel = ivBuildWheel(loadout, id)
            val melee = wheel.at(VWheelSlot.Melee)
            assertTrue(melee.isSelected)
            assertEquals(IV_WEAPON_UNARMED, melee.shown?.hash?.toInt())
        }
    }

    @Test
    fun meleeWeaponInHandIsShownInsteadOfFists() {
        val melee = ivBuildWheel(loadout, bat).at(VWheelSlot.Melee)
        assertTrue(melee.isSelected)
        assertEquals(bat, melee.shown?.hash?.toInt())
    }

    @Test
    fun weaponTheWheelDoesNotListSelectsNothing() {
        // In hand but not in `owned`: an unscanned slot, or the list is
        // up to a second old.
        val wheel = ivBuildWheel(loadout, rpg)
        assertEquals(0, wheel.count { it.isSelected })
    }

    @Test
    fun thrownAndSpecialShareASlotInGameSlotOrder() {
        val special = 30
        val owned = listOf(
            OwnedWeapon(slot = 9, id = special, ammo = 1),
            OwnedWeapon(slot = 8, id = grenade, ammo = 5),
        )
        assertEquals(listOf(grenade, special),
                     ivBuildWheel(owned, null).at(VWheelSlot.Thrown).ids())
    }

    @Test
    fun junkEntriesAreIgnored() {
        val owned = listOf(
            OwnedWeapon(slot = 2, id = 0, ammo = 0),     // empty slot
            OwnedWeapon(slot = 11, id = 41, ammo = 1),   // parachute slot
            OwnedWeapon(slot = 6, id = sniper, ammo = 10),
        )
        val wheel = ivBuildWheel(owned, null)
        assertTrue(wheel.at(VWheelSlot.Pistols).weapons.isEmpty())
        assertEquals(listOf(sniper), wheel.at(VWheelSlot.Snipers).ids())
        assertFalse(wheel.any { seg -> seg.weapons.any { it.hash == 41L } })
    }

    // ---- taps (shared rules, GTA IV ids) --------------------------------

    @Test
    fun tapOnAnotherSlotAsksForItsWeaponById() {
        val wheel = ivBuildWheel(loadout, pistol)
        assertEquals(ak47.toLong(), vWheelTapTarget(wheel.at(VWheelSlot.Rifles)))
        // Melee shows fists first: one tap holsters.
        assertEquals(IV_WEAPON_UNARMED.toLong(), vWheelTapTarget(wheel.at(VWheelSlot.Melee)))
    }

    @Test
    fun tappingMeleeAgainStepsBetweenFistsAndTheMeleeWeapon() {
        val unarmed = ivBuildWheel(loadout, IV_WEAPON_UNARMED)
        assertEquals(bat.toLong(), vWheelTapTarget(unarmed.at(VWheelSlot.Melee)))
        val withBat = ivBuildWheel(loadout, bat)
        assertEquals(IV_WEAPON_UNARMED.toLong(), vWheelTapTarget(withBat.at(VWheelSlot.Melee)))
    }

    @Test
    fun tapsThatDoNothing() {
        val wheel = ivBuildWheel(loadout, pistol)
        assertNull(vWheelTapTarget(wheel.at(VWheelSlot.Snipers)))   // empty
        assertNull(vWheelTapTarget(wheel.at(VWheelSlot.Pistols)))   // already in hand
    }
}
