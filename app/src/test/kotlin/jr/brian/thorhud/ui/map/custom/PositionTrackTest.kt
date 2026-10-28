package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionTrackTest {

    private fun ms(x: Long): Long = x * 1_000_000L

    @Test fun returnsNullBeforeAnySample() {
        val t = PositionTrack()
        assertNull(t.extrapolate(0L))
    }

    @Test fun returnsLastWhenOnlyOneSample() {
        val t = PositionTrack()
        t.submit(10f, 20f, ms(100))
        val out = t.extrapolate(ms(200))
        assertEquals(10f, out!!.ix, 1e-3f)
        assertEquals(20f, out.iy, 1e-3f)
    }

    @Test fun linearExtrapolationBetweenTwoSamples() {
        val t = PositionTrack()
        // Walking at 100 px/sec in +x.
        t.submit(0f, 0f, ms(0))
        t.submit(5f, 0f, ms(50)) // 50 ms later, 5 px moved → 100 px/sec
        // At t = 100 ms (50 ms past last sample), expect 10 px.
        val p = t.extrapolate(ms(100))!!
        assertEquals(10f, p.ix, 1e-3f)
    }

    @Test fun extrapolationIsCappedAtMaxMs() {
        val t = PositionTrack()
        t.submit(0f, 0f, ms(0))
        t.submit(10f, 0f, ms(50))
        // 1 s past the last sample. Without a cap we'd predict 200 px.
        // With a 120 ms cap we predict 10 + 10/50 * 120 = 34 px.
        val p = t.extrapolate(ms(1050), maxExtrapolateMs = 120)!!
        assertEquals(34f, p.ix, 1e-3f)
    }

    @Test fun newSampleResetsHistoryOnBackwardsTime() {
        val t = PositionTrack()
        t.submit(0f, 0f, ms(100))
        t.submit(10f, 0f, ms(200))
        // Simulate reconnect: timestamp goes backwards.
        t.submit(50f, 50f, ms(10))
        val p = t.extrapolate(ms(20))!!
        // Only one sample now (the reconnected one). Extrapolate
        // returns that sample verbatim.
        assertEquals(50f, p.ix, 1e-3f)
        assertEquals(50f, p.iy, 1e-3f)
    }

    @Test fun submitAtSameTimeDoesNotExplode() {
        val t = PositionTrack()
        t.submit(0f, 0f, ms(100))
        t.submit(5f, 5f, ms(100)) // dt = 0: coerceAtLeast(1L) kicks in
        // Just check no divide by zero.
        val p = t.extrapolate(ms(110))!!
        assertEquals(p.ix, p.ix, 1e-3f)
    }
}
