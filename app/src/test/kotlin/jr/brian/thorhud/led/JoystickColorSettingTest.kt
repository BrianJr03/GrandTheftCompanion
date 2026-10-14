package jr.brian.thorhud.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the wire format AYN control-lite writes to its picker settings:
 * `joystick_led_light_picker_color` and `led_light_brightness_percent`.
 */
class JoystickColorSettingTest {

    // ---- parseColors ------------------------------------------------

    @Test
    fun parsesTwoArgbTokens() {
        val (l, r) = JoystickColorSetting.parseColors("#ff00ff00,#ffff008c")!!
        assertEquals(0xff00ff00.toInt(), l)
        assertEquals(0xffff008c.toInt(), r)
    }

    @Test
    fun leftAndRightCanDiffer() {
        val (l, r) = JoystickColorSetting.parseColors("#ffff0000,#ff00ff00")!!
        assertEquals(0xffff0000.toInt(), l)
        assertEquals(0xff00ff00.toInt(), r)
    }

    @Test
    fun whitespaceAroundTokensIsFine() {
        val parsed = JoystickColorSetting.parseColors(" #ff00ff00 , #ff00ff00 ")!!
        assertEquals(0xff00ff00.toInt(), parsed.first)
        assertEquals(0xff00ff00.toInt(), parsed.second)
    }

    // Settings.System.getString returns Java null for a missing key;
    // the "null" literal shows up in `adb settings get` output only.
    // Guard against both so callers get a single sentinel.
    @Test
    fun missingColorKeyReturnsNull() {
        assertNull(JoystickColorSetting.parseColors(null))
        assertNull(JoystickColorSetting.parseColors(""))
        assertNull(JoystickColorSetting.parseColors("null"))
    }

    @Test
    fun malformedTokensReturnNull() {
        assertNull(JoystickColorSetting.parseColors("#ff00ff00"))          // one token
        assertNull(JoystickColorSetting.parseColors("#ff00ff,#ff00ff00"))  // short left
        assertNull(JoystickColorSetting.parseColors("ff00ff00,#ff00ff00")) // missing #
        assertNull(JoystickColorSetting.parseColors("#gg00ff00,#ff00ff00"))// non-hex
    }

    // ---- parseBrightness --------------------------------------------

    @Test
    fun brightnessMapsZeroToOneOntoByteRange() {
        assertEquals(0, JoystickColorSetting.parseBrightness("0.0"))
        assertEquals(255, JoystickColorSetting.parseBrightness("1.0"))
        // 0.04 is the picker's dimmest step we observed on-device.
        assertEquals(10, JoystickColorSetting.parseBrightness("0.04"))
        assertEquals(127, JoystickColorSetting.parseBrightness("0.5"))
    }

    @Test
    fun brightnessClampsOutOfRange() {
        assertEquals(0, JoystickColorSetting.parseBrightness("-0.3"))
        assertEquals(255, JoystickColorSetting.parseBrightness("1.7"))
    }

    @Test
    fun brightnessReturnsNullForUnparseable() {
        assertNull(JoystickColorSetting.parseBrightness(null))
        assertNull(JoystickColorSetting.parseBrightness(""))
        assertNull(JoystickColorSetting.parseBrightness("null"))
        assertNull(JoystickColorSetting.parseBrightness("abc"))
    }
}
