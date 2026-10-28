package jr.brian.thorhud.ui.map.custom

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
 * Image-space coordinates throughout; the caller converts world->
 * image via the calibration before feeding samples in.
 */
class PositionTrack {

    private var prev: Sample? = null
    private var last: Sample? = null

    data class Sample(val ix: Float, val iy: Float, val nanos: Long)

    /** Record a new 20 Hz sample. Monotonic timestamps required; a
     *  non-monotonic arrival (plugin reconnected, nanoTime wrapped)
     *  resets the track to just this sample. */
    fun submit(ix: Float, iy: Float, nanos: Long) {
        val l = last
        if (l != null && nanos < l.nanos) {
            prev = null
            last = Sample(ix, iy, nanos)
            return
        }
        prev = l
        last = Sample(ix, iy, nanos)
    }

    fun clear() { prev = null; last = null }

    /** Latest known position, no extrapolation. Null until the first
     *  sample. */
    fun latest(): Sample? = last

    /** Linearly extrapolated image position at [nowNanos].
     *  Returns null until at least one sample has been submitted. */
    fun extrapolate(nowNanos: Long, maxExtrapolateMs: Int = kMaxExtrapolateMs): Sample? {
        val l = last ?: return null
        val p = prev ?: return l
        val sampleDtNanos = (l.nanos - p.nanos).coerceAtLeast(1L)
        val vxPerNs = (l.ix - p.ix) / sampleDtNanos.toFloat()
        val vyPerNs = (l.iy - p.iy) / sampleDtNanos.toFloat()
        val rawDt = (nowNanos - l.nanos).coerceAtLeast(0L)
        val dt = rawDt.coerceAtMost(maxExtrapolateMs * 1_000_000L)
        return Sample(
            ix = l.ix + vxPerNs * dt.toFloat(),
            iy = l.iy + vyPerNs * dt.toFloat(),
            nanos = nowNanos,
        )
    }

    companion object {
        /** Hard cap on how far past the newest sample to extrapolate.
         *  At 20 Hz sample arrival the natural "fill" is up to 50 ms;
         *  120 ms gives a bit of headroom for a dropped sample without
         *  letting a stopped player visibly overshoot. */
        const val kMaxExtrapolateMs: Int = 120
    }
}
