package jr.brian.thorhud.ui.map.custom

import kotlin.math.hypot

/**
 * 20 Hz position samples arrive from the plugin as discrete points.
 * The render clock runs at the display refresh (90 Hz on the Thor), so
 * naively centring on the latest sample stepps the camera 20 times a
 * second and feels like a strobe. [PositionTrack] keeps the last two
 * samples and extrapolates linearly between arrivals, so a render at
 * any time between (or shortly after) samples returns a smooth
 * position estimate.
 *
 * The extrapolation is capped at [kMaxExtrapolateMs] past the newest
 * sample so a stationary player at the edge of a sample window does
 * not drift. A new sample becomes the current target; the smoothing
 * stage above absorbs any disagreement between the extrapolation and
 * the real position.
 *
 * Heading travels on each sample too. It is extrapolated the same way
 * as position, but through shortest-arc interpolation so a sample
 * crossing from 350 degrees to 10 degrees moves through 0, not back
 * through 180.
 *
 * Image-space coordinates throughout; the caller converts world->
 * image via the calibration before feeding samples in.
 */
class PositionTrack {

    private var prev: Sample? = null
    private var last: Sample? = null

    data class Sample(
        val ix: Float, val iy: Float,
        /** Heading in degrees, 0..360. Null means "no heading on this
         *  sample"; the extrapolator returns the last known non-null
         *  heading in that case. */
        val headingDeg: Float?,
        val nanos: Long,
    )

    /** Record a new 20 Hz sample. Monotonic timestamps required; a
     *  non-monotonic arrival (plugin reconnected, nanoTime wrapped)
     *  resets the track to just this sample. */
    fun submit(ix: Float, iy: Float, headingDeg: Float?, nanos: Long) {
        val l = last
        if (l != null && nanos < l.nanos) {
            prev = null
            last = Sample(ix, iy, headingDeg, nanos)
            return
        }
        prev = l
        last = Sample(ix, iy, headingDeg, nanos)
    }

    /** Position-only submit kept for callers that don't track heading
     *  (none in the shipping app, but tests use it). */
    fun submit(ix: Float, iy: Float, nanos: Long) {
        submit(ix, iy, headingDeg = null, nanos)
    }

    fun clear() { prev = null; last = null }

    fun latest(): Sample? = last

    /** Linearly extrapolated image position at [nowNanos].
     *  Returns null until at least one sample has been submitted.
     *  Heading uses shortest-arc interpolation across the 0/360 seam. */
    fun extrapolate(nowNanos: Long, maxExtrapolateMs: Int = kMaxExtrapolateMs): Sample? {
        val l = last ?: return null
        val p = prev ?: return l
        val sampleDtNanos = (l.nanos - p.nanos).coerceAtLeast(1L)
        val vxPerNs = (l.ix - p.ix) / sampleDtNanos.toFloat()
        val vyPerNs = (l.iy - p.iy) / sampleDtNanos.toFloat()
        val rawDt = (nowNanos - l.nanos).coerceAtLeast(0L)
        val dt = rawDt.coerceAtMost(maxExtrapolateMs * 1_000_000L)
        val dtF = dt.toFloat()

        val headingOut = extrapolateHeading(p.headingDeg, l.headingDeg, sampleDtNanos, dt)
        return Sample(
            ix = l.ix + vxPerNs * dtF,
            iy = l.iy + vyPerNs * dtF,
            headingDeg = headingOut,
            nanos = nowNanos,
        )
    }

    /** Image-space px/sec speed from the last two samples, or null
     *  until two samples exist. Caller converts to m/s via the
     *  calibration's metres-per-image-pixel if desired. */
    fun speedPxPerSec(): Float? {
        val l = last ?: return null
        val p = prev ?: return null
        val dt = (l.nanos - p.nanos) / 1e9f
        if (dt <= 0f) return null
        return hypot(l.ix - p.ix, l.iy - p.iy) / dt
    }

    private fun extrapolateHeading(
        prevHeading: Float?, lastHeading: Float?,
        sampleDtNanos: Long, dtNanos: Long,
    ): Float? {
        if (lastHeading == null) return prevHeading
        if (prevHeading == null || sampleDtNanos <= 0L) return lastHeading
        val delta = shortestArcDeg(prevHeading, lastHeading)
        val vPerNs = delta / sampleDtNanos.toFloat()
        val out = lastHeading + vPerNs * dtNanos.toFloat()
        return normalise360(out)
    }

    companion object {
        /** Hard cap on how far past the newest sample to extrapolate.
         *  At 20 Hz sample arrival the natural "fill" is up to 50 ms;
         *  120 ms gives a bit of headroom for a dropped sample without
         *  letting a stopped player visibly overshoot. */
        const val kMaxExtrapolateMs: Int = 120

        /** Signed shortest arc from [a] to [b], in degrees, in
         *  [-180, 180]. 350 -> 10 returns +20; 10 -> 350 returns -20. */
        fun shortestArcDeg(a: Float, b: Float): Float {
            val diff = ((b - a) % 360f + 540f) % 360f - 180f
            return diff
        }

        fun normalise360(deg: Float): Float {
            var out = deg % 360f
            if (out < 0f) out += 360f
            return out
        }
    }
}
