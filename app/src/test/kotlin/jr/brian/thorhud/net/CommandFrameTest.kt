package jr.brian.thorhud.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client->server command wire format. The expected strings are what
 * both plugins' parse_cmd accept (and what asi-v/test/run_mock_test.py
 * sends).
 */
class CommandFrameTest {

    @Test
    fun weaponHashAboveIntMaxStaysUnsigned() {
        // joaat("weapon_knife"). As an Int this is -1716189206, and the
        // plugin, which skips to the first digit, would read 1716189206.
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":3,\"name\":\"set_weapon\"," +
                "\"args\":{\"weapon\":2578778090}}\n",
            commandFrame(3, "set_weapon", 2578778090L),
        )
    }

    @Test
    fun radioKeepsItsArgumentName() {
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":1,\"name\":\"set_radio\"," +
                "\"args\":{\"station\":255}}\n",
            commandFrame(1, "set_radio", 255L),
        )
    }

    @Test
    fun routeBlipCarriesTheBlipId() {
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":4,\"name\":\"route_blip\"," +
                "\"args\":{\"blip\":301}}\n",
            commandFrame(4, "route_blip", 301L),
        )
    }

    @Test
    fun cheatCarriesTheId() {
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":5,\"name\":\"cheat\"," +
                "\"args\":{\"cheat\":7}}\n",
            commandFrame(5, "cheat", 7L),
        )
    }

    @Test
    fun paintRgbCarriesPackedColour() {
        // 0x00B21E1E = 11,673,118 (a swatch red). The arg key is "rgb",
        // not "cheat", so the plugin's commands.cpp can tell this from
        // the id-shaped cheat command.
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":6,\"name\":\"paint_rgb\"," +
                "\"args\":{\"rgb\":11673118}}\n",
            commandFrame(6, "paint_rgb", 0x00B21E1EL),
        )
    }

    @Test
    fun unknownCommandUsesGenericArgumentName() {
        val line = commandFrame(9, "future_cmd", 1L)
        assertNotNull(line)
        assertTrue(line!!.contains("\"args\":{\"value\":1}"))
    }

    @Test
    fun wholeUnsignedRangeIsAccepted() {
        assertNotNull(commandFrame(1, "set_weapon", 0L))
        assertNotNull(commandFrame(1, "set_weapon", 0xFFFFFFFFL))
    }

    @Test
    fun valuesThePluginsCannotParseAreRefused() {
        assertNull(commandFrame(1, "set_weapon", -1L))
        assertNull(commandFrame(1, "set_weapon", 0x100000000L))
    }

    @Test
    fun everyFrameIsOneLine() {
        val line = commandFrame(1, "set_weapon", 453432689L)!!
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
    }

    @Test
    fun setModUsesModArgKey() {
        // (slot<<16)|index — 11 << 16 | 2 = 720,898.
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":2,\"name\":\"set_mod\"," +
                "\"args\":{\"mod\":720898}}\n",
            commandFrame(2, "set_mod", ((11L shl 16) or 2L)),
        )
    }

    @Test
    fun toggleModUsesSlotArgKey() {
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":3,\"name\":\"toggle_mod\"," +
                "\"args\":{\"slot\":18}}\n",
            commandFrame(3, "toggle_mod", 18L),
        )
    }

    @Test
    fun waypointXyCarriesBothCoords() {
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":7,\"name\":\"waypoint_xy\"," +
                "\"args\":{\"x\":123.5,\"y\":-67.25}}\n",
            commandFrameXy(7, "waypoint_xy", 123.5f, -67.25f),
        )
    }

    @Test
    fun waypointXyTrimsTrailingZeros() {
        // Whole-number coordinates should not drag "123.00".
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":1,\"name\":\"waypoint_xy\"," +
                "\"args\":{\"x\":123,\"y\":0}}\n",
            commandFrameXy(1, "waypoint_xy", 123.0f, 0.0f),
        )
    }

    @Test
    fun setWheelTypeUsesTypeArgKey() {
        assertTrue(commandFrame(4, "set_wheel_type", 1L)!!
            .contains("\"args\":{\"type\":1}"))
    }

    @Test
    fun setTintUsesTintArgKey() {
        assertTrue(commandFrame(5, "set_tint", 3L)!!
            .contains("\"args\":{\"tint\":3}"))
    }

    @Test
    fun setPlateStyleUsesStyleArgKey() {
        assertTrue(commandFrame(6, "set_plate_style", 2L)!!
            .contains("\"args\":{\"style\":2}"))
    }

    @Test
    fun setNeonUsesRgbArgKeyAndAcceptsZeroForOff() {
        assertTrue(commandFrame(7, "set_neon", 0L)!!
            .contains("\"args\":{\"rgb\":0}"))
        assertTrue(commandFrame(7, "set_neon", 0x00FF8800L)!!
            .contains("\"args\":{\"rgb\":16746496}"))
    }

    @Test
    fun setBulletproofTyresUsesOnArgKey() {
        assertTrue(commandFrame(8, "set_bulletproof_tyres", 1L)!!
            .contains("\"args\":{\"on\":1}"))
    }

    @Test
    fun modsListUsesVArgKey() {
        // The arg itself is ignored by the plugin; the key just has to
        // be present so parse_cmd finds the first digit.
        assertTrue(commandFrame(9, "mods_list", 0L)!!
            .contains("\"args\":{\"v\":0}"))
    }

    @Test
    fun setPlateCarriesStringArg() {
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":10,\"name\":\"set_plate\"," +
                "\"args\":{\"text\":\"GTC 123\"}}\n",
            commandFrameText(10, "set_plate", "GTC 123"),
        )
    }

    @Test
    fun setPlateTextRefusesLongOrControlOrEscaped() {
        assertNull(commandFrameText(11, "set_plate", "VERYLONGPLATE"))
        assertNull(commandFrameText(12, "set_plate", "A\"B"))
        assertNull(commandFrameText(13, "set_plate", "A\\B"))
        assertNull(commandFrameText(14, "set_plate", "A\nB"))
    }

    // ---- Ammu-Nation ------------------------------------------------

    @Test
    fun armoryListCarriesWeaponHashUnsigned() {
        // weapon_pistol_mk2 = 0xBFE256D4 (verified in Gate A0): the
        // hash is above Int.MAX_VALUE, so Long is mandatory on the
        // wire or the plugin receives a different weapon.
        val mk2 = 0xBFE256D4L
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":20,\"name\":\"armory_list\"," +
                "\"args\":{\"weapon\":$mk2}}\n",
            commandFrame(20, "armory_list", mk2),
        )
    }

    @Test
    fun setComponentPacksWidxCidxOn() {
        // widx=32, cidx=5, on=true → (32<<16)|(5<<1)|1 = 2097163.
        val arg = ((32L shl 16) or (5L shl 1) or 1L)
        assertEquals(2097163L, arg)
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":21,\"name\":\"set_component\"," +
                "\"args\":{\"arg\":$arg}}\n",
            commandFrame(21, "set_component", arg),
        )
    }

    @Test
    fun setWeaponTintPacksWidxTint() {
        // widx=32, tint=5 → (32<<16)|5 = 2097157.
        val arg = (32L shl 16) or 5L
        assertEquals(2097157L, arg)
        assertTrue(commandFrame(22, "set_weapon_tint", arg)!!
            .contains("\"args\":{\"arg\":$arg}"))
    }

    @Test
    fun setAmmoPacksWidxAction() {
        // widx=4, action=0 (fill) and action=1 (+1 clip).
        val fill = (4L shl 16) or 0L
        val add  = (4L shl 16) or 1L
        assertTrue(commandFrame(23, "set_ammo", fill)!!.contains("\"arg\":$fill"))
        assertTrue(commandFrame(24, "set_ammo", add)!!.contains("\"arg\":$add"))
    }

    @Test
    fun waypointXyHandlesNegativeAndSmallFractions() {
        val line = commandFrameXy(2, "waypoint_xy", -0.5f, 10.1f)
        assertTrue(line.contains("\"x\":-0.5"))
        assertTrue(line.contains("\"y\":10.1"))
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun singleArgFramesAreUnchangedByThisChange() {
        // Byte-identical to the pre-pos change — these frames still
        // encode the same way the plugin and run_mock_test.py expect.
        assertEquals(
            "{\"v\":1,\"type\":\"cmd\",\"id\":1,\"name\":\"route_clear\"," +
                "\"args\":{\"value\":0}}\n",
            commandFrame(1, "route_clear", 0L),
        )
    }
}
