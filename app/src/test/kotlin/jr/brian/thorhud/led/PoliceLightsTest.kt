package jr.brian.thorhud.led

import jr.brian.thorhud.model.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the wanted-level stick lights: trigger, tempo, colours. */
class PoliceLightsTest {

    private fun playing(wanted: Int?, game: String? = null) =
        GameState(type = "state", game = game, inGame = true, wanted = wanted)

    // ---- trigger ----------------------------------------------------

    @Test
    fun oneStarIsEnoughToFlash() {
        assertEquals(1, PoliceLights.wantedLevel(playing(wanted = 1)))
        assertEquals(4, PoliceLights.wantedLevel(playing(wanted = 4)))
    }

    @Test
    fun noStarsNoFlash() {
        assertEquals(0, PoliceLights.wantedLevel(null))
        assertEquals(0, PoliceLights.wantedLevel(playing(wanted = 0)))
        assertEquals(0, PoliceLights.wantedLevel(playing(wanted = null)))
        assertEquals(0, PoliceLights.wantedLevel(playing(wanted = -1)))
    }

    @Test
    fun noFlashWhileTheGameIsNotBeingPlayed() {
        val wanted = playing(wanted = 3)
        assertEquals(0, PoliceLights.wantedLevel(wanted.copy(inGame = false)))
        assertEquals(0, PoliceLights.wantedLevel(wanted.copy(paused = true)))
        assertEquals(0, PoliceLights.wantedLevel(wanted.copy(stalled = true)))
        assertEquals(0, PoliceLights.wantedLevel(wanted.copy(online = true)))
        assertEquals(0, PoliceLights.wantedLevel(wanted.copy(switching = true)))
    }

    @Test
    fun starCountFollowsTheGame() {
        assertEquals(6, PoliceLights.maxStars(playing(wanted = 1)))
        assertEquals(5, PoliceLights.maxStars(playing(wanted = 1, game = GameState.GAME_V)))
        assertEquals(6, PoliceLights.maxStars(null))
    }

    // ---- tempo ------------------------------------------------------

    @Test
    fun oneStarIsTheSlowestBeatAndFullStarsTheFastest() {
        assertEquals(PoliceLights.SLOWEST_BEAT_MS, PoliceLights.beatIntervalMs(1, 6))
        assertEquals(PoliceLights.FASTEST_BEAT_MS, PoliceLights.beatIntervalMs(6, 6))
        assertEquals(PoliceLights.SLOWEST_BEAT_MS, PoliceLights.beatIntervalMs(1, 5))
        assertEquals(PoliceLights.FASTEST_BEAT_MS, PoliceLights.beatIntervalMs(5, 5))
    }

    @Test
    fun everyStarQuickensTheBeat() {
        for (maxStars in listOf(5, 6)) {
            val beats = (1..maxStars).map { PoliceLights.beatIntervalMs(it, maxStars) }
            assertEquals("stars 1..$maxStars: $beats", beats.sortedDescending(), beats)
            assertEquals("stars 1..$maxStars: $beats", beats.size, beats.distinct().size)
        }
    }

    @Test
    fun beatNeverLeavesItsLimitsWhateverTheGameReports() {
        for (maxStars in -1..8) {
            for (wanted in -5..200) {
                val ms = PoliceLights.beatIntervalMs(wanted, maxStars)
                assertTrue(
                    "wanted=$wanted maxStars=$maxStars -> $ms ms",
                    ms in PoliceLights.FASTEST_BEAT_MS..PoliceLights.SLOWEST_BEAT_MS,
                )
            }
        }
    }

    @Test
    fun limitsRespectWhatBifrostAllows() {
        // Bifrost accepts four commands a second; at three or fewer
        // there is always a token left over for the clear.
        assertTrue(PoliceLights.FASTEST_BEAT_MS >= 334L)
        // Bifrost reverts an override it has not heard from in 1.5 s.
        // Two of our slowest beats must fit, so one can go missing.
        assertTrue(PoliceLights.SLOWEST_BEAT_MS * 2 < 1500L)
    }

    // ---- colours ----------------------------------------------------

    @Test
    fun sticksTradeRedAndBlueEveryBeat() {
        assertEquals(PoliceLights.RED to PoliceLights.BLUE, PoliceLights.colorsForBeat(0))
        assertEquals(PoliceLights.BLUE to PoliceLights.RED, PoliceLights.colorsForBeat(1))
        assertEquals(PoliceLights.RED to PoliceLights.BLUE, PoliceLights.colorsForBeat(2))
        // Survives the beat counter wrapping round.
        assertEquals(
            PoliceLights.colorsForBeat(1),
            PoliceLights.colorsForBeat(Int.MAX_VALUE),
        )
        assertEquals(
            PoliceLights.colorsForBeat(0),
            PoliceLights.colorsForBeat(Int.MIN_VALUE),
        )
    }

    // ---- Bifrost result codes --------------------------------------

    @Test
    fun resultCodesMapToStatus() {
        assertEquals(StickLights.Status.Ready, BifrostClient.statusFor(0))
        assertEquals(StickLights.Status.Disabled, BifrostClient.statusFor(-1))
        // Rate-limited (-5) and friends say nothing about availability.
        assertNull(BifrostClient.statusFor(-5))
        assertNull(BifrostClient.statusFor(BifrostClient.RESULT_UNANSWERED))
    }

    @Test
    fun onlyKnownFailuresCountAsUnavailable() {
        assertTrue(StickLights.Status.NotInstalled.isUnavailable)
        assertTrue(StickLights.Status.Disabled.isUnavailable)
        assertTrue(StickLights.Status.NoAnswer.isUnavailable)
        assertTrue(!StickLights.Status.Ready.isUnavailable)
        assertTrue(!StickLights.Status.Unknown.isUnavailable)
    }
}
