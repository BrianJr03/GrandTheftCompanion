package jr.brian.thorhud.ui.v

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VWeaponWheelModelTest {

    private fun weapon(name: String, group: String, ammo: Int = 0) =
        VWheelWeapon(vJoaat(name), name, vJoaat(group), ammo)

    private val pistol = weapon("weapon_pistol", "GROUP_PISTOL", 120)
    private val combatPistol = weapon("weapon_combatpistol", "GROUP_PISTOL", 60)
    private val apPistol = weapon("weapon_appistol", "GROUP_PISTOL", 200)
    private val knife = weapon("weapon_knife", "GROUP_MELEE")
    private val rifle = weapon("weapon_assaultrifle", "GROUP_RIFLE", 300)
    private val grenade = weapon("weapon_grenade", "GROUP_THROWN", 5)
    private val parachute = weapon("gadget_parachute", "GROUP_PARACHUTE")
    private val loadout =
        listOf(knife, pistol, combatPistol, apPistol, rifle, grenade, parachute)

    private fun List<VWheelSegment>.at(slot: VWheelSlot) = this[slot.ordinal]

    // ---- hashing -------------------------------------------------------

    @Test
    fun joaatMatchesThePlugin() {
        // Reference values from asi-v/src/joaat.h and the wire fixtures
        // in GameStateVTest.
        assertEquals(0x1B06D571L, vJoaat("weapon_pistol"))
        assertEquals(2578778090L, vJoaat("weapon_knife"))
        assertEquals(V_WEAPON_UNARMED_HASH, vJoaat("weapon_unarmed"))
        assertEquals(416676503L, vJoaat("GROUP_PISTOL"))
    }

    @Test
    fun joaatIgnoresCase() {
        assertEquals(vJoaat("group_pistol"), vJoaat("GROUP_PISTOL"))
    }

    // ---- which slot ----------------------------------------------------

    @Test
    fun groupsMapToTheGamesSlots() {
        fun slot(group: String) = vWheelSlotOf("weapon_x", vJoaat(group))
        assertEquals(VWheelSlot.Pistols, slot("GROUP_PISTOL"))
        assertEquals(VWheelSlot.Pistols, slot("GROUP_STUNGUN"))
        assertEquals(VWheelSlot.Smgs, slot("GROUP_SMG"))
        assertEquals(VWheelSlot.Smgs, slot("GROUP_MG"))
        assertEquals(VWheelSlot.Rifles, slot("GROUP_RIFLE"))
        assertEquals(VWheelSlot.Snipers, slot("GROUP_SNIPER"))
        assertEquals(VWheelSlot.Melee, slot("GROUP_MELEE"))
        assertEquals(VWheelSlot.Melee, slot("GROUP_UNARMED"))
        assertEquals(VWheelSlot.Shotguns, slot("GROUP_SHOTGUN"))
        assertEquals(VWheelSlot.Heavy, slot("GROUP_HEAVY"))
        assertEquals(VWheelSlot.Thrown, slot("GROUP_THROWN"))
        assertEquals(VWheelSlot.Thrown, slot("GROUP_PETROLCAN"))
        assertEquals(VWheelSlot.Thrown, slot("GROUP_FIREEXTINGUISHER"))
    }

    @Test
    fun unknownGroupStaysReachableAndGadgetsAreLeftOut() {
        assertEquals(VWheelSlot.Thrown, vWheelSlotOf("weapon_new", 12345L))
        assertNull(vWheelSlotOf("gadget_parachute", vJoaat("GROUP_THROWN")))
    }

    @Test
    fun slotOfHash() {
        assertEquals(VWheelSlot.Pistols, vWheelSlotOfHash(loadout, apPistol.hash))
        assertEquals(VWheelSlot.Melee, vWheelSlotOfHash(loadout, V_WEAPON_UNARMED_HASH))
        assertNull(vWheelSlotOfHash(loadout, null))
        assertNull(vWheelSlotOfHash(loadout, 0L))
        assertNull(vWheelSlotOfHash(loadout, 0xDEADBEEFL))   // not owned
        assertNull(vWheelSlotOfHash(loadout, parachute.hash))
    }

    // ---- layout --------------------------------------------------------

    @Test
    fun alwaysEightSegmentsInSlotOrder() {
        val wheel = vBuildWheel(emptyList(), null, emptyMap())
        assertEquals(VWheelSlot.entries, wheel.map { it.slot })
    }

    @Test
    fun emptyLoadoutStillOffersFists() {
        val wheel = vBuildWheel(emptyList(), null, emptyMap())
        val melee = wheel.at(VWheelSlot.Melee)
        assertEquals(listOf(V_WEAPON_UNARMED_HASH), melee.weapons.map { it.hash })
        assertTrue(melee.isSelected)   // no weapon = fists selected
        for (segment in wheel.filter { it.slot != VWheelSlot.Melee }) {
            assertTrue(segment.weapons.isEmpty())
            assertEquals(-1, segment.shownIndex)
            assertNull(segment.shown)
            assertFalse(segment.isSelected)
        }
    }

    @Test
    fun weaponsLandInTheirSlotsInPluginOrder() {
        val wheel = vBuildWheel(loadout, pistol.hash, emptyMap())
        assertEquals(listOf(pistol, combatPistol, apPistol),
                     wheel.at(VWheelSlot.Pistols).weapons)
        assertEquals(listOf(V_WEAPON_UNARMED_HASH, knife.hash),
                     wheel.at(VWheelSlot.Melee).weapons.map { it.hash })
        assertEquals(listOf(rifle), wheel.at(VWheelSlot.Rifles).weapons)
        assertEquals(listOf(grenade), wheel.at(VWheelSlot.Thrown).weapons)
        // The parachute is owned but is not a wheel weapon.
        assertFalse(wheel.any { seg -> seg.weapons.any { it.hash == parachute.hash } })
    }

    @Test
    fun selectedWeaponIsShownAndMarked() {
        val wheel = vBuildWheel(loadout, combatPistol.hash, emptyMap())
        val pistols = wheel.at(VWheelSlot.Pistols)
        assertTrue(pistols.isSelected)
        assertEquals(combatPistol, pistols.shown)
        assertEquals(1, wheel.count { it.isSelected })
    }

    @Test
    fun unarmedInAnyFormSelectsFists() {
        for (hash in listOf(null, 0L, V_WEAPON_UNARMED_HASH)) {
            val wheel = vBuildWheel(loadout, hash, emptyMap())
            assertTrue(wheel.at(VWheelSlot.Melee).isSelected)
            assertEquals(V_WEAPON_UNARMED_HASH, wheel.at(VWheelSlot.Melee).shown?.hash)
        }
    }

    @Test
    fun weaponTheWheelDoesNotKnowSelectsNothing() {
        val wheel = vBuildWheel(loadout, 0xDEADBEEFL, emptyMap())
        assertEquals(0, wheel.count { it.isSelected })
    }

    @Test
    fun unselectedSlotShowsRememberedWeaponElseItsFirst() {
        val remembered = mapOf(VWheelSlot.Pistols to apPistol.hash)
        val wheel = vBuildWheel(loadout, rifle.hash, remembered)
        assertEquals(apPistol, wheel.at(VWheelSlot.Pistols).shown)
        assertFalse(wheel.at(VWheelSlot.Pistols).isSelected)
        // Nothing remembered for melee: first entry, which is fists.
        assertEquals(V_WEAPON_UNARMED_HASH, wheel.at(VWheelSlot.Melee).shown?.hash)
    }

    @Test
    fun rememberedWeaponNoLongerOwnedFallsBackToFirst() {
        val remembered = mapOf(VWheelSlot.Pistols to 0xDEADBEEFL)
        val wheel = vBuildWheel(loadout, rifle.hash, remembered)
        assertEquals(pistol, wheel.at(VWheelSlot.Pistols).shown)
    }

    @Test
    fun selectionBeatsMemory() {
        val remembered = mapOf(VWheelSlot.Pistols to apPistol.hash)
        val wheel = vBuildWheel(loadout, pistol.hash, remembered)
        assertEquals(pistol, wheel.at(VWheelSlot.Pistols).shown)
    }

    // ---- taps ----------------------------------------------------------

    @Test
    fun tapOnAnotherSlotPicksWhatItShows() {
        val wheel = vBuildWheel(loadout, rifle.hash, emptyMap())
        assertEquals(pistol.hash, vWheelTapTarget(wheel.at(VWheelSlot.Pistols)))
        assertEquals(V_WEAPON_UNARMED_HASH, vWheelTapTarget(wheel.at(VWheelSlot.Melee)))
    }

    @Test
    fun tapOnTheSelectedSlotCyclesAndWraps() {
        var selected = pistol.hash
        val seen = mutableListOf<Long>()
        repeat(3) {
            val wheel = vBuildWheel(loadout, selected, emptyMap())
            selected = vWheelTapTarget(wheel.at(VWheelSlot.Pistols))!!
            seen += selected
        }
        assertEquals(listOf(combatPistol.hash, apPistol.hash, pistol.hash), seen)
    }

    @Test
    fun tapsThatDoNothing() {
        val wheel = vBuildWheel(loadout, rifle.hash, emptyMap())
        assertNull(vWheelTapTarget(wheel.at(VWheelSlot.Shotguns)))   // empty slot
        assertNull(vWheelTapTarget(wheel.at(VWheelSlot.Rifles)))     // only weapon, already selected
    }

    // ---- hit-testing ---------------------------------------------------

    @Test
    fun cardinalDirectionsHitTheExpectedSlots() {
        val r = 100f
        val mid = r * (1f + V_WHEEL_INNER_FRACTION) / 2f
        // Screen coordinates: +x right, +y down.
        assertEquals(VWheelSlot.Pistols.ordinal, vWheelSegmentAt(mid, 0f, r))
        assertEquals(VWheelSlot.Rifles.ordinal, vWheelSegmentAt(0f, mid, r))
        assertEquals(VWheelSlot.Melee.ordinal, vWheelSegmentAt(-mid, 0f, r))
        assertEquals(VWheelSlot.Heavy.ordinal, vWheelSegmentAt(0f, -mid, r))
    }

    @Test
    fun diagonalsHitTheExpectedSlots() {
        val r = 100f
        val d = r * (1f + V_WHEEL_INNER_FRACTION) / 2f * 0.7071f
        assertEquals(VWheelSlot.Smgs.ordinal, vWheelSegmentAt(d, d, r))
        assertEquals(VWheelSlot.Snipers.ordinal, vWheelSegmentAt(-d, d, r))
        assertEquals(VWheelSlot.Shotguns.ordinal, vWheelSegmentAt(-d, -d, r))
        assertEquals(VWheelSlot.Thrown.ordinal, vWheelSegmentAt(d, -d, r))
    }

    @Test
    fun slotBoundariesFallHalfASlotEitherSideOfCentre() {
        val r = 100f
        val mid = 70.0
        fun at(deg: Double): Int? {
            val rad = Math.toRadians(deg)
            return vWheelSegmentAt((mid * Math.cos(rad)).toFloat(),
                                   (mid * Math.sin(rad)).toFloat(), r)
        }
        assertEquals(0, at(-22.0))
        assertEquals(0, at(22.0))
        assertEquals(1, at(23.0))
        assertEquals(7, at(-23.0))
        assertEquals(4, at(180.0))
        assertEquals(4, at(-179.0))
    }

    // ---- reorder ------------------------------------------------------

    @Test
    fun reorderSlidesSlotsAlong() {
        val base = VWheelSlot.entries
        // Moving slot 0 (Pistols) three spots clockwise pushes
        // 1..3 (SMGs, Rifles, Snipers) back one each.
        assertEquals(
            listOf(
                VWheelSlot.Smgs, VWheelSlot.Rifles, VWheelSlot.Snipers,
                VWheelSlot.Pistols, VWheelSlot.Melee, VWheelSlot.Shotguns,
                VWheelSlot.Heavy, VWheelSlot.Thrown,
            ),
            wheelReorder(base, from = 0, to = 3),
        )
        // Moving backward: Snipers (3) lands at 1, SMGs and Rifles slide
        // one spot the other way.
        assertEquals(
            listOf(
                VWheelSlot.Pistols, VWheelSlot.Snipers, VWheelSlot.Smgs,
                VWheelSlot.Rifles, VWheelSlot.Melee, VWheelSlot.Shotguns,
                VWheelSlot.Heavy, VWheelSlot.Thrown,
            ),
            wheelReorder(base, from = 3, to = 1),
        )
    }

    @Test
    fun reorderIsANoOpWhenFromEqualsToOrOutOfRange() {
        val base = VWheelSlot.entries
        assertEquals(base, wheelReorder(base, 3, 3))
        assertEquals(base, wheelReorder(base, -1, 2))
        assertEquals(base, wheelReorder(base, 2, 99))
    }

    @Test
    fun hubAndOutsideHitNothing() {
        val r = 100f
        assertNull(vWheelSegmentAt(0f, 0f, r))
        assertNull(vWheelSegmentAt(r * V_WHEEL_INNER_FRACTION - 1f, 0f, r))
        assertNull(vWheelSegmentAt(r + 1f, 0f, r))
        assertNull(vWheelSegmentAt(80f, 80f, r))   // corner of the box, beyond the rim
    }
}
