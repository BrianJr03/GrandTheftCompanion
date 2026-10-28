package jr.brian.thorhud.ui.map.custom

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The atlas page's rendering camera.
 *
 * Carries the **rendered** camera (what the Canvas is drawing right
 * now) and a set of optional motion states: a [target] for smooth
 * follow, a [flyTo] animation (set when follow re-engages or on a
 * teleport), and a [fling] with exponential decay (set on gesture
 * lift). [step] advances the rendered camera by one display frame
 * using these states; it is deterministic in `dt` so a dropped frame
 * never changes the path.
 *
 * The camera lives in image-pixel space; conversion to a
 * [ViewportModel] (which the Canvas understands) is a one-liner via
 * [toViewport]. Direct gesture updates (pan/zoom-about-focal) build
 * a new camera via [panBy] / [zoomAbout], which clear [target] /
 * [flyTo] / [fling] as appropriate.
 *
 * Pure Kotlin; unit-testable.
 */
data class AtlasCamera(
    val centerImageX: Float,
    val centerImageY: Float,
    val scale: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    /** Smooth-follow target. When set, [step] eases toward it. */
    val target: Target? = null,
    /** Fly-to animation. Takes priority over [target]. */
    val flyTo: FlyTo? = null,
    /** Fling decay. Supersedes nothing; coexists with [target] in
     *  theory, but any pan clears both so in practice they don't
     *  overlap. */
    val fling: Fling? = null,
    /** Rendered rotation of the image about the viewport center, in
     *  degrees of clockwise rotation (what Compose's rotate() takes).
     *  Normalised to [0, 360). */
    val rotationDeg: Float = 0f,
    /** Target rotation (0..360). Null means "freeze": step() leaves
     *  rotationDeg where it is. Used for the on-foot low-speed hold
     *  so a looking-around ped doesn't make the map twitch. */
    val rotationTarget: Float? = null,
    val lastStepNanos: Long = 0L,
) {

    data class Target(
        val centerImageX: Float,
        val centerImageY: Float,
        val scale: Float,
    )

    data class FlyTo(
        val startNanos: Long,
        val durationNanos: Long,
        val fromX: Float, val fromY: Float, val fromScale: Float,
        val toX: Float, val toY: Float, val toScale: Float,
        /** Scale at the midpoint for the "zoom out, move, zoom in" arc
         *  when the jump is large. Equals min(fromScale, toScale) for
         *  short hops (no arc). */
        val midScale: Float,
    )

    data class Fling(
        /** Image-space pixels per second. */
        val vxPxPerSec: Float,
        val vyPxPerSec: Float,
        val tauSeconds: Float,
    )

    /** Build the [ViewportModel] the Canvas consumes. */
    fun toViewport(): ViewportModel {
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        return ViewportModel(
            scale = scale,
            offsetX = cx - centerImageX,
            offsetY = cy - centerImageY,
            rotationDeg = rotationDeg,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
    }

    /** Set a rotation target. Null freezes rotation smoothing (used
     *  by the low-speed hold on foot). */
    fun withRotationTarget(targetDeg: Float?): AtlasCamera =
        copy(rotationTarget = targetDeg?.let { PositionTrack.normalise360(it) })

    /** Snap the rendered rotation and clear the target. Used by the
     *  "ease to north-up" shortcut and tests. */
    fun snapRotation(deg: Float): AtlasCamera =
        copy(rotationDeg = PositionTrack.normalise360(deg), rotationTarget = null)

    /** Snap to a new center and scale, clearing any motion state. */
    fun snapTo(ix: Float, iy: Float, newScale: Float = scale): AtlasCamera {
        val s = newScale.coerceIn(ViewportModel.MIN_SCALE, ViewportModel.MAX_SCALE)
        val clamped = clampCenter(ix, iy, s)
        return copy(
            centerImageX = clamped.first,
            centerImageY = clamped.second,
            scale = s,
            target = null, flyTo = null, fling = null,
        )
    }

    /** Set a follow target without disturbing any in-flight motion.
     *  Smoothing kicks in on the next [step] once flyTo and fling (if
     *  any) have finished; the step priority ladder handles that. */
    fun withTarget(target: Target?): AtlasCamera = copy(target = target)

    /** Direct pan from a gesture. Clears all motion state because the
     *  user is driving. */
    fun panBy(deltaScreenX: Float, deltaScreenY: Float): AtlasCamera {
        if (scale <= 0f) return this
        val nx = centerImageX - deltaScreenX / scale
        val ny = centerImageY - deltaScreenY / scale
        val clamped = clampCenter(nx, ny, scale)
        return copy(
            centerImageX = clamped.first,
            centerImageY = clamped.second,
            target = null, flyTo = null, fling = null,
        )
    }

    /** Zoom about a screen-space focal point. Keeps the image point
     *  under the focal fixed. Does not clear [target] (so pinch while
     *  following stays follow-centric; the caller rebinds the target
     *  if it moved the camera through a different code path). */
    fun zoomAbout(
        zoom: Float,
        focalScreenX: Float,
        focalScreenY: Float,
    ): AtlasCamera {
        val newScale = (scale * zoom).coerceIn(ViewportModel.MIN_SCALE, ViewportModel.MAX_SCALE)
        if (newScale == scale) return this
        // Image point under the focal before zoom:
        //   ix = cx + (focalScreenX - screenW/2) / scale
        val ix = centerImageX + (focalScreenX - screenWidth / 2f) / scale
        val iy = centerImageY + (focalScreenY - screenHeight / 2f) / scale
        // After zoom we want the same ix under the focal:
        //   centerNew = ix - (focalScreenX - screenW/2) / newScale
        val cxNew = ix - (focalScreenX - screenWidth / 2f) / newScale
        val cyNew = iy - (focalScreenY - screenHeight / 2f) / newScale
        val clamped = clampCenter(cxNew, cyNew, newScale)
        return copy(
            scale = newScale,
            centerImageX = clamped.first,
            centerImageY = clamped.second,
            // Fly-to and fling can't survive a direct zoom; target
            // stays live so a follow+pinch keeps following.
            flyTo = null, fling = null,
        )
    }

    /** Start a fly-to animation to the given world-space target. If
     *  the jump exceeds [jumpFraction] of the viewport diagonal, the
     *  animation uses an arc: midpoint scale is the smaller of
     *  [fromScale] and the scale that would show both ends with a
     *  60% padding, so the player gets a sense of "zoom out, move,
     *  zoom in". For short hops, no arc (midScale = fromScale). */
    fun startFlyTo(
        toX: Float, toY: Float, toScale: Float,
        nowNanos: Long,
        durationMs: Int = kFlyToDurationMs,
        jumpFraction: Float = kFlyToJumpFraction,
    ): AtlasCamera {
        val targetScale = toScale.coerceIn(ViewportModel.MIN_SCALE, ViewportModel.MAX_SCALE)
        val screenDist = hypot(
            (toX - centerImageX) * scale,
            (toY - centerImageY) * scale,
        )
        val viewportDiag = hypot(screenWidth.toFloat(), screenHeight.toFloat())
        val mid = if (screenDist > viewportDiag * jumpFraction) {
            // Zoom out enough to show both endpoints. Use the smaller
            // (coarser) of the current/target scales as a baseline, then
            // ensure both fit in the viewport with 20% padding.
            val worldSpan = hypot(toX - centerImageX, toY - centerImageY).coerceAtLeast(1f)
            val fitBoth = min(screenWidth, screenHeight) / (worldSpan * 2.4f)
            min(scale, min(targetScale, fitBoth))
        } else {
            min(scale, targetScale)
        }
        return copy(
            flyTo = FlyTo(
                startNanos = nowNanos,
                durationNanos = durationMs.toLong() * 1_000_000L,
                fromX = centerImageX, fromY = centerImageY, fromScale = scale,
                toX = toX, toY = toY, toScale = targetScale,
                midScale = mid,
            ),
            fling = null,
        )
    }

    /** Start a fling after a lift. [vxPxPerSec] / [vyPxPerSec] are in
     *  **screen** space; converted to image-space internally. */
    fun startFling(
        vxScreenPxPerSec: Float,
        vyScreenPxPerSec: Float,
        tauSeconds: Float = kFlingTauSeconds,
    ): AtlasCamera {
        if (scale <= 0f) return this
        val speed = hypot(vxScreenPxPerSec, vyScreenPxPerSec)
        if (speed < kMinFlingSpeedPxPerSec) return copy(fling = null)
        return copy(
            fling = Fling(
                vxPxPerSec = -vxScreenPxPerSec / scale,
                vyPxPerSec = -vyScreenPxPerSec / scale,
                tauSeconds = tauSeconds,
            ),
            flyTo = null,
        )
    }

    /** Advance by one display frame.
     *
     *  Priority ladder:
     *  1. If a [flyTo] is active and not expired, interpolate.
     *  2. Else if [fling] is active, step it and apply remaining
     *     velocity to the center.
     *  3. Else if [target] is set, exp-smooth toward it; dead-zone
     *     under [deadZonePx] suppresses sub-pixel drift. */
    fun step(
        nowNanos: Long,
        timeConstantMs: Int = kFollowTimeConstantMs,
        deadZonePx: Float = kDeadZonePx,
        rotationTimeConstantMs: Int = kRotationTimeConstantMs,
        rotationDeadZoneDeg: Float = kRotationDeadZoneDeg,
    ): AtlasCamera {
        val dtNanos = if (lastStepNanos == 0L) 0L
                      else (nowNanos - lastStepNanos).coerceAtLeast(0L)
        val dtSec = dtNanos / 1e9f

        // Rotation smoothing runs independently of flyTo/fling/target
        // because the pivot moves with the camera center, so rotating
        // mid-flight simply plays alongside without a seam. Dead zone
        // and the "null target freezes" rule live inside this block.
        val rotationNew: Float = run {
            val targetRot = rotationTarget ?: return@run rotationDeg
            if (dtSec <= 0f) return@run rotationDeg
            val delta = PositionTrack.shortestArcDeg(rotationDeg, targetRot)
            if (abs(delta) <= rotationDeadZoneDeg) return@run rotationDeg
            val tauSec = (rotationTimeConstantMs / 1000f).coerceAtLeast(1e-3f)
            val alpha = 1f - exp(-dtSec / tauSec)
            PositionTrack.normalise360(rotationDeg + delta * alpha)
        }

        flyTo?.let { ft ->
            val t = ((nowNanos - ft.startNanos).toFloat() / ft.durationNanos.toFloat())
                .coerceIn(0f, 1f)
            if (t >= 1f) {
                return copy(
                    centerImageX = ft.toX, centerImageY = ft.toY,
                    scale = ft.toScale,
                    rotationDeg = rotationNew,
                    flyTo = null,
                    lastStepNanos = nowNanos,
                )
            }
            val e = easeInOut(t)
            val nx = lerp(ft.fromX, ft.toX, e)
            val ny = lerp(ft.fromY, ft.toY, e)
            // Scale uses a two-segment path through midScale so a long
            // jump zooms out at the midpoint. For short hops midScale
            // equals min(fromScale, toScale), so the two segments
            // collapse to a straight ease.
            val sExp = if (t < 0.5f) {
                lerpLog(ft.fromScale, ft.midScale, easeInOut(t * 2f))
            } else {
                lerpLog(ft.midScale, ft.toScale, easeInOut((t - 0.5f) * 2f))
            }
            val clamped = clampCenter(nx, ny, sExp)
            return copy(
                centerImageX = clamped.first,
                centerImageY = clamped.second,
                scale = sExp,
                rotationDeg = rotationNew,
                lastStepNanos = nowNanos,
            )
        }

        fling?.let { fl ->
            // Exponential decay of velocity, position integrated
            // analytically over dt.
            if (dtSec <= 0f) return@let
            val tau = fl.tauSeconds.coerceAtLeast(1e-3f)
            val k = exp(-dtSec / tau)
            // Distance travelled during this slice:
            //   dx = v0 * tau * (1 - exp(-dt/tau))
            val dx = fl.vxPxPerSec * tau * (1f - k)
            val dy = fl.vyPxPerSec * tau * (1f - k)
            val nx = centerImageX + dx
            val ny = centerImageY + dy
            val clamped = clampCenter(nx, ny, scale)
            val vx = fl.vxPxPerSec * k
            val vy = fl.vyPxPerSec * k
            val still = hypot(vx, vy) * scale < kMinFlingSpeedPxPerSec * 0.5f
            return copy(
                centerImageX = clamped.first,
                centerImageY = clamped.second,
                rotationDeg = rotationNew,
                fling = if (still) null else fl.copy(vxPxPerSec = vx, vyPxPerSec = vy),
                lastStepNanos = nowNanos,
            )
        }

        target?.let { t ->
            if (dtSec <= 0f) return copy(
                rotationDeg = rotationNew,
                lastStepNanos = nowNanos,
            )
            val dx = t.centerImageX - centerImageX
            val dy = t.centerImageY - centerImageY
            val screenDist = hypot(dx, dy) * scale
            if (screenDist <= deadZonePx) {
                return copy(
                    rotationDeg = rotationNew,
                    lastStepNanos = nowNanos,
                )
            }
            val tauSec = (timeConstantMs / 1000f).coerceAtLeast(1e-3f)
            val alpha = 1f - exp(-dtSec / tauSec)
            val nx = centerImageX + dx * alpha
            val ny = centerImageY + dy * alpha
            val sNew = scale + (t.scale - scale) * alpha
            val clamped = clampCenter(nx, ny, sNew)
            return copy(
                centerImageX = clamped.first,
                centerImageY = clamped.second,
                scale = sNew,
                rotationDeg = rotationNew,
                lastStepNanos = nowNanos,
            )
        }

        return copy(rotationDeg = rotationNew, lastStepNanos = nowNanos)
    }

    /** Clamp the camera center so the image always *covers* the
     *  viewport. The player can drift off-center near a map edge but
     *  the Canvas never shows black on either side. When the viewport
     *  is bigger than the image at the current scale (zoomed out past
     *  fit), the camera snaps to the image center on that axis. */
    private fun clampCenter(ix: Float, iy: Float, s: Float): Pair<Float, Float> {
        if (s <= 0f) return ix to iy
        val halfViewportImgX = screenWidth  / (2f * s)
        val halfViewportImgY = screenHeight / (2f * s)
        val cxImage = imageWidth  / 2f
        val cyImage = imageHeight / 2f
        val xMin: Float; val xMax: Float
        if (halfViewportImgX * 2f >= imageWidth) {
            xMin = cxImage; xMax = cxImage
        } else {
            xMin = halfViewportImgX
            xMax = imageWidth - halfViewportImgX
        }
        val yMin: Float; val yMax: Float
        if (halfViewportImgY * 2f >= imageHeight) {
            yMin = cyImage; yMax = cyImage
        } else {
            yMin = halfViewportImgY
            yMax = imageHeight - halfViewportImgY
        }
        return ix.coerceIn(xMin, xMax) to iy.coerceIn(yMin, yMax)
    }

    companion object {
        /** Follow smoothing time constant: higher = looser trail. */
        const val kFollowTimeConstantMs: Int = 120
        /** Sub-pixel-shimmer suppression under follow. */
        const val kDeadZonePx: Float = 2f
        /** Fly-to default duration. */
        const val kFlyToDurationMs: Int = 450
        /** Treat a target more than this fraction of the viewport
         *  diagonal away as a long jump (use the arc). */
        const val kFlyToJumpFraction: Float = 0.40f
        /** Fling decay time constant (velocity falls to 1/e in this
         *  many seconds). */
        const val kFlingTauSeconds: Float = 0.45f
        /** Flings below this (in screen px / sec) are suppressed; a
         *  slow pan at lift-off shouldn't coast. */
        const val kMinFlingSpeedPxPerSec: Float = 80f

        /** Rotation smoothing time constant: higher = looser. 180 ms
         *  is softer than the position follow (120 ms) because a map
         *  that rotates faster than it translates reads as jittery. */
        const val kRotationTimeConstantMs: Int = 180
        /** Degree-level dead zone on the rotation smoother so a
         *  stationary target doesn't produce sub-pixel wobble. */
        const val kRotationDeadZoneDeg: Float = 1.5f
        /** Time constant for one-shot "ease rotation back to north"
         *  transitions (pan-off, setting toggle). Slower than the
         *  heading-follow so a pan feels like a settle, not a snap. */
        const val kRotationEaseOffMs: Int = 400
        /** Image-space speed under which the heading-up target is
         *  frozen (on foot, the ped's heading flickers while looking
         *  around with the stick). The caller translates image px/sec
         *  to m/sec via the calibration before comparing. */
        const val kLowSpeedHoldMps: Float = 0.6f

        fun fromViewport(vp: ViewportModel): AtlasCamera {
            val cx = vp.imageWidth / 2f
            val cy = vp.imageHeight / 2f
            return AtlasCamera(
                centerImageX = cx - vp.offsetX,
                centerImageY = cy - vp.offsetY,
                scale = vp.scale,
                rotationDeg = vp.rotationDeg,
                imageWidth = vp.imageWidth,
                imageHeight = vp.imageHeight,
                screenWidth = vp.screenWidth,
                screenHeight = vp.screenHeight,
            )
        }

        private fun easeInOut(t: Float): Float {
            // Smootherstep. Zero slope at both ends.
            val c = t.coerceIn(0f, 1f)
            return c * c * c * (c * (c * 6f - 15f) + 10f)
        }
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
        private fun lerpLog(a: Float, b: Float, t: Float): Float {
            if (a <= 0f || b <= 0f) return lerp(a, b, t)
            return exp(lerp(ln(a), ln(b), t))
        }
    }
}

/** Factor out the smoothing-time choices so UI and tests share a
 *  single source of truth. The strings in [label] are user-visible. */
enum class FollowSmoothing(val tauMs: Int, val label: String) {
    Tight (60, "tight"),
    Normal(120, "normal"),
    Loose (240, "loose");

    companion object {
        fun fromTauMs(tauMs: Int): FollowSmoothing =
            values().minByOrNull { abs(it.tauMs - tauMs) } ?: Normal
    }
}
