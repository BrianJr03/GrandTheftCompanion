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
}
