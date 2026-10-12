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

    // ---- restoreCommand (ambient-snapshot replay) -------------------

    @Test
    fun restoreReplaysBothZonesPerStickFromRawReads() {
        // The sysfs read format matches the write format on the Thor.
        // Replay captures both zone lines verbatim and sends them to
        // the right node.
        val left = "1-10:20:30:255\n2-10:20:30:255"
        val right = "1-40:50:60:255\n2-40:50:60:255"
        assertEquals(
            "echo 1-10:20:30:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 2-10:20:30:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 1-40:50:60:255 > /sys/class/sn3112r/led/brightness && " +
                "echo 2-40:50:60:255 > /sys/class/sn3112r/led/brightness",
            Sn3112.restoreCommand(left, right),
        )
    }

    @Test
    fun restoreToleratesTrailingNewlinesAndBlankLines() {
        val left = "\n1-5:5:5:255\n\n2-5:5:5:255\n"
        val right = "1-7:7:7:255\n"
        val cmd = Sn3112.restoreCommand(left, right)
        assertEquals(
            "echo 1-5:5:5:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 2-5:5:5:255 > /sys/class/sn3112l/led/brightness && " +
                "echo 1-7:7:7:255 > /sys/class/sn3112r/led/brightness",
            cmd,
        )
    }

    @Test
    fun restoreSkipsCommentLinesIfDriverAnnotatesReads() {
        // Not every SN3112 revision emits comments, but the hotfix is
        // defensive: a '#' at the start of a line means metadata, drop
        // it so we don't try to `echo #...` into the node.
        val left = "# zone map\n1-9:9:9:255\n2-9:9:9:255"
        val right = "1-9:9:9:255\n2-9:9:9:255"
        val cmd = Sn3112.restoreCommand(left, right)
        assertTrue(!cmd.contains("#"))
        assertTrue(cmd.contains("echo 1-9:9:9:255 > /sys/class/sn3112l"))
    }

    @Test
    fun restoreFallsBackToBlackWhenBothReadsWereAllCommentsOrBlank() {
        // If the sysfs returned nothing we can replay, don't build an
        // empty command — the SN3112 would do nothing and the sticks
        // would be stuck on whatever the beat loop last showed.
        assertEquals(Sn3112.clearCommand(), Sn3112.restoreCommand("# only metadata", "\n\n"))
    }
}
