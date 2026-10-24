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
