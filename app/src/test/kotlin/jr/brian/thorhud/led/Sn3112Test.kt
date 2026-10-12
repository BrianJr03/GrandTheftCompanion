package jr.brian.thorhud.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact wire format for the Thor's SN3112 LED kernel driver.
 * Pinned so a refactor can't silently change what PServer sees — any
 * drift from `echo <zone>-R:G:B:A > /sys/class/sn3112{l,r}/led/brightness`
 * is the same as writing to the wrong driver.
 */
class Sn3112Test {

    @Test
    fun redLeftBlueRightBuildsTheExpectedShellCommand() {
        val cmd = Sn3112.dualCommand(PoliceLights.RED, PoliceLights.BLUE)
        assertEquals(
            "echo 1-255:0:0:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 2-255:0:0:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 1-0:0:255:255 > /sys/class/sn3112r/led/brightness && " +
                "echo 2-0:0:255:255 > /sys/class/sn3112r/led/brightness",
            cmd,
        )
    }

    @Test
    fun swappedColoursSwapTheSticks() {
        // Beat 1 of the police pattern: left blue, right red.
        val cmd = Sn3112.dualCommand(PoliceLights.BLUE, PoliceLights.RED)
        assertEquals(
            "echo 1-0:0:255:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 2-0:0:255:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 1-255:0:0:255 > /sys/class/sn3112r/led/brightness && " +
                "echo 2-255:0:0:255 > /sys/class/sn3112r/led/brightness",
            cmd,
        )
    }

    @Test
    fun clearIsBlackOnBothSticks() {
        assertEquals(Sn3112.dualCommand(0, 0), Sn3112.clearCommand())
        assertTrue(Sn3112.clearCommand().contains("0:0:0:255"))
    }

    @Test
    fun alphaChannelIsIgnoredRgbOnly() {
        // Bifrost's EXTRA_COLOR is ARGB; the kernel driver only cares
        // about R/G/B. Dropping alpha here keeps the two paths visually
        // equivalent for the same ARGB input.
        val withAlpha = Sn3112.dualCommand(0x80FF0000.toInt(), 0x800000FF.toInt())
        val noAlpha = Sn3112.dualCommand(0x00FF0000, 0x000000FF)
        assertEquals(noAlpha, withAlpha)
    }

    @Test
    fun bothZonesPerStickShareTheColourSoTheRingReadsAsOne() {
        val cmd = Sn3112.dualCommand(0x00AABBCC, 0x00112233)
        // Zone 1 and zone 2 for the left stick are the same triplet.
        assertTrue(cmd.contains("1-170:187:204:255 > /sys/class/sn3112l"))
        assertTrue(cmd.contains("2-170:187:204:255 > /sys/class/sn3112l"))
        assertTrue(cmd.contains("1-17:34:51:255 > /sys/class/sn3112r"))
        assertTrue(cmd.contains("2-17:34:51:255 > /sys/class/sn3112r"))
    }
}
