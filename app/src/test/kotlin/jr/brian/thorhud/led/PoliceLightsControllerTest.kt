package jr.brian.thorhud.led

import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [PoliceLightsController] on virtual time against a fake pair
 * of sticks and checks what it sends, and when.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoliceLightsControllerTest {

    private val state = MutableStateFlow<GameState?>(null)
    private val lastUpdateMs = MutableStateFlow(0L)
    private val enabled = MutableStateFlow(true)

    /** While true, [TestScope.startGame] keeps "frames" arriving. */
    private var pluginAlive = true

    private class Show(val atMs: Long, val left: Int, val right: Int)

    private class FakeLights(private val now: () -> Long) : StickLights {
        override val status: StateFlow<StickLights.Status> =
            MutableStateFlow(StickLights.Status.Unknown)
        val shows = mutableListOf<Show>()
        var clears = 0
        var statusChecks = 0
        /** What the sticks are doing right now. */
        var lit = false

        override fun show(left: Int, right: Int) {
            shows += Show(now(), left, right)
            lit = true
        }
        override fun clear() { clears++; lit = false }
        override fun refreshStatus() { statusChecks++; lit = false }

        fun gaps(): List<Long> = shows.zipWithNext { a, b -> b.atMs - a.atMs }
    }

    private fun wanted(stars: Int, game: String? = null, paused: Boolean = false) =
        GameState(type = "state", game = game, inGame = true, paused = paused, wanted = stars)

    /** Starts the controller plus a stand-in for the plugin's 20 Hz frames. */
    private fun TestScope.startGame(): FakeLights {
        val lights = FakeLights { currentTime }
        // currentTime starts at 0, which the repository uses for "no
        // frame yet", so the stand-in clock runs one second ahead.
        val clock = { currentTime + 1_000 }
        backgroundScope.launch {
            while (true) {
                if (pluginAlive) lastUpdateMs.value = clock()
                delay(50)
            }
        }
        backgroundScope.launch {
            PoliceLightsController(state, lastUpdateMs, enabled, lights, clock).run()
        }
        runCurrent()
        return lights
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun sendsNoColoursWithoutStars() = runTest {
        state.value = wanted(0)
        val lights = startGame()
        advance(10_000)
        assertTrue(lights.shows.isEmpty())
        assertEquals(0, lights.clears)
        // One look at whether Bifrost is there, not a poll.
        assertEquals(1, lights.statusChecks)
    }

    @Test
    fun flashesRedAndBlueFromTheFirstStar() = runTest {
        state.value = wanted(1)
        val lights = startGame()
        advance(PoliceLights.SLOWEST_BEAT_MS * 3)

        assertEquals(4, lights.shows.size)
        assertEquals(0L, lights.shows.first().atMs)
        lights.shows.forEachIndexed { i, show ->
            assertEquals(PoliceLights.colorsForBeat(i), show.left to show.right)
        }
        assertTrue(lights.gaps().all { it == PoliceLights.SLOWEST_BEAT_MS })
        // Nothing but colours while the chase is on.
        assertEquals(0, lights.clears)
        assertEquals(0, lights.statusChecks)
    }

    @Test
    fun moreStarsFlashFasterButNeverPastTheFloor() = runTest {
        state.value = wanted(1)
        val lights = startGame()
        advance(PoliceLights.SLOWEST_BEAT_MS * 2)

        // A star count no game should report still gets the floor.
        state.value = wanted(99)
        advance(10_000)

        val gaps = lights.gaps()
        assertEquals(PoliceLights.FASTEST_BEAT_MS, gaps.last())
        assertEquals(PoliceLights.FASTEST_BEAT_MS, gaps.min())
        assertTrue(gaps.all { it >= PoliceLights.FASTEST_BEAT_MS })
    }

    @Test
    fun gainingAStarChangesTheTempoWithoutAnExtraFlash() = runTest {
        state.value = wanted(1, game = GameState.GAME_V)
        val lights = startGame()
        advance(100)
        assertEquals(1, lights.shows.size)

        // Mid-beat. The beat in progress finishes at the old length.
        state.value = wanted(5, game = GameState.GAME_V)
        advance(100)
        assertEquals(1, lights.shows.size)

        advance(PoliceLights.SLOWEST_BEAT_MS - 200 + PoliceLights.FASTEST_BEAT_MS)
        assertEquals(
            listOf(PoliceLights.SLOWEST_BEAT_MS, PoliceLights.FASTEST_BEAT_MS),
            lights.gaps(),
        )
        // And the colours kept alternating across the change.
        lights.shows.forEachIndexed { i, show ->
            assertEquals(PoliceLights.colorsForBeat(i), show.left to show.right)
        }
    }

    @Test
    fun staysWithinBifrostsFourCommandsASecond() = runTest {
        state.value = wanted(6)
        val lights = startGame()
        advance(60_000)
        val perSecond = lights.shows.groupingBy { it.atMs / 1000 }.eachCount()
        assertTrue("$perSecond", perSecond.values.all { it <= 3 })
    }

    @Test
    fun losingTheStarsHandsTheSticksBack() = runTest {
        state.value = wanted(3)
        val lights = startGame()
        advance(2_000)
        assertTrue(lights.lit)

        state.value = wanted(0)
        advance(10_000)
        assertTrue(!lights.lit)
        assertEquals(1, lights.clears)
        assertEquals(0, lights.statusChecks)
        assertTrue(lights.shows.last().atMs <= 2_000)
    }

    @Test
    fun pauseKeepsTheLightsFlashingBecauseTheHeatPersists() = runTest {
        state.value = wanted(2)
        val lights = startGame()
        advance(1_000)
        val shownBeforePause = lights.shows.size

        state.value = wanted(2, paused = true)
        advance(5_000)
        assertTrue(lights.lit)
        assertTrue(lights.shows.size > shownBeforePause)
    }

    @Test
    fun theSwitchTurnsThemOffAndOn() = runTest {
        state.value = wanted(2)
        val lights = startGame()
        advance(1_000)
        assertTrue(lights.lit)

        enabled.value = false
        advance(5_000)
        assertTrue(!lights.lit)
        assertEquals(1, lights.clears)
        val shownWhileOn = lights.shows.size

        enabled.value = true
        advance(1_000)
        assertTrue(lights.lit)
        assertTrue(lights.shows.size > shownWhileOn)
    }

    @Test
    fun switchedOffSendsNothingAtAll() = runTest {
        enabled.value = false
        state.value = wanted(4)
        val lights = startGame()
        advance(10_000)
        assertTrue(lights.shows.isEmpty())
        assertEquals(0, lights.clears)
        assertEquals(0, lights.statusChecks)
    }

    @Test
    fun letsGoWhenTheGameStopsSendingFramesAndResumesWhenItReturns() = runTest {
        state.value = wanted(4)
        val lights = startGame()
        advance(2_000)
        assertTrue(lights.lit)

        // Game closed or hung mid-chase: the last state, stars and all,
        // stays in the repository.
        pluginAlive = false
        advance(PoliceLights.STALE_AFTER_MS + 1_000)
        assertTrue(!lights.lit)
        val shownBeforeStall = lights.shows.size
        advance(10_000)
        assertEquals(shownBeforeStall, lights.shows.size)
        assertEquals(1, lights.clears)

        pluginAlive = true
        advance(1_000)
        assertTrue(lights.lit)
    }

    @Test
    fun cancellingHandsTheSticksBack() = runTest {
        state.value = wanted(3)
        val lights = FakeLights { currentTime }
        lastUpdateMs.value = 1_000
        val job = launch {
            PoliceLightsController(state, lastUpdateMs, enabled, lights) { 1_000 }.run()
        }
        runCurrent()
        assertTrue(lights.lit)

        job.cancelAndJoin()
        assertTrue(!lights.lit)
        assertEquals(1, lights.clears)
    }
}
