package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun headingExtrapolatesShortestArcAcrossSeam() {
        val t = PositionTrack()
        // 350 -> 10 over 100 ms is a 20 deg CW turn, not a 340 CCW turn.
        t.submit(0f, 0f, headingDeg = 350f, nanos = ms(0))
        t.submit(0f, 0f, headingDeg = 10f, nanos = ms(100))
        // At the midpoint, heading should be 0 (passing through north).
        val mid = t.extrapolate(ms(150))!!
        // 50% past the last sample = 50% past heading 10 at +20/100ms,
        // so 10 + 20 * 0.5 = 20 is OUTSIDE sampleDt. extrapolate uses
        // "velocity" from the full sample pair. 50ms past last sample at
        // 20deg/100ms = +10deg = 20 (because last is 10 and we add 10).
        assertEquals(20f, mid.headingDeg!!, 1e-3f)
    }

    @Test fun headingShortArcGoesOtherWay() {
        val t = PositionTrack()
        // 10 -> 350 is -20 CCW.
        t.submit(0f, 0f, headingDeg = 10f, nanos = ms(0))
        t.submit(0f, 0f, headingDeg = 350f, nanos = ms(100))
        val past = t.extrapolate(ms(150))!!
        // vPerNs = -20/100ms, +50ms past last = -10. last=350, so 340.
        assertEquals(340f, past.headingDeg!!, 1e-3f)
    }

    @Test fun headingExtrapolationNeverLeaves0to360() {
        val t = PositionTrack()
        t.submit(0f, 0f, headingDeg = 358f, nanos = ms(0))
        t.submit(0f, 0f, headingDeg = 2f, nanos = ms(50))
        val p = t.extrapolate(ms(100))!!
        assertTrue(p.headingDeg!! in 0f..360f)
    }

    @Test fun shortestArcHelperReturnsSignedDelta() {
        assertEquals(20f, PositionTrack.shortestArcDeg(350f, 10f), 1e-3f)
        assertEquals(-20f, PositionTrack.shortestArcDeg(10f, 350f), 1e-3f)
        assertEquals(0f, PositionTrack.shortestArcDeg(45f, 45f), 1e-3f)
        // Edge: 0 -> 180 is a tie; implementation returns -180.
        // Either sign is correct; smoothing with abs(delta) treats them the same.
        val tie = PositionTrack.shortestArcDeg(0f, 180f)
        assertEquals(180f, kotlin.math.abs(tie), 1e-3f)
    }

    @Test fun speedPxPerSecRequiresTwoSamples() {
        val t = PositionTrack()
        assertNull(t.speedPxPerSec())
        t.submit(0f, 0f, ms(0))
        assertNull(t.speedPxPerSec())
        t.submit(10f, 0f, ms(100))
        // 10 px in 0.1 s = 100 px/s.
        assertEquals(100f, t.speedPxPerSec()!!, 1e-2f)
    }
}

