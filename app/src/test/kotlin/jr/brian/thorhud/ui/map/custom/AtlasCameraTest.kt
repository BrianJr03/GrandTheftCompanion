package jr.brian.thorhud.ui.map.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AtlasCameraTest {

    private fun base(): AtlasCamera = AtlasCamera(
        centerImageX = 500f, centerImageY = 400f,
        scale = 1.0f,
        imageWidth = 1000, imageHeight = 800,
        screenWidth = 500, screenHeight = 400,
    )

    private fun ms(nanos: Long): Long = nanos * 1_000_000L

    @Test fun snapToClearsMotionAndCentres() {
        val c = base().withTarget(AtlasCamera.Target(300f, 300f, 1f))
            .startFlyTo(10f, 10f, 2f, nowNanos = 0L)
        // At scale 2 the clamp window is [125, 875] x [100, 700]
        // for this 1000x800 image + 500x400 viewport, so (500, 400)
        // is inside and the exact coords survive.
        val snapped = c.snapTo(500f, 400f, 2f)
        assertNull(snapped.target); assertNull(snapped.flyTo); assertNull(snapped.fling)
        assertEquals(500f, snapped.centerImageX, 1e-3f)
        assertEquals(400f, snapped.centerImageY, 1e-3f)
        assertEquals(2f, snapped.scale, 1e-3f)
    }

    @Test fun stationaryTargetProducesZeroMotionAfterSettle() {
        // Target equal to current centre: dead-zone + exp smoothing
        // should settle inside 1 px after 0.5 s.
        var c = base().withTarget(AtlasCamera.Target(500f, 400f, 1f))
        var t = 0L
        val dt = ms(11) // 90 Hz
        repeat(50) {
            t += dt
            c = c.step(t, timeConstantMs = 120, deadZonePx = 2f)
        }
        assertEquals(500f, c.centerImageX, 1f)
        assertEquals(400f, c.centerImageY, 1f)
    }

    @Test fun targetJumpProducesMonotonicApproach() {
        var c = base().withTarget(AtlasCamera.Target(550f, 400f, 1f))
        var last = c.centerImageX
        var t = 0L
        repeat(80) {
            t += ms(11)
            c = c.step(t, timeConstantMs = 120, deadZonePx = 0f)
            assertTrue("approach x not monotonic at step $it: $last -> ${c.centerImageX}",
                c.centerImageX >= last - 1e-3f)
            last = c.centerImageX
        }
        // 80 steps * 11 ms = 880 ms = ~7 tau; well under 1 px of target.
        assertEquals(550f, c.centerImageX, 1f)
    }

    @Test fun constantVelocityTargetHasBoundedLag() {
        // Target moves at 50 image-px/sec (walking speed under a modest
        // calibration). After settling, lag in screen px should be
        // under the time-constant distance * scale.
        var c = base().withTarget(AtlasCamera.Target(500f, 400f, 1f))
        var t = 0L
        val vxImgPerSec = 50f
        var targetX = 500f
        var targetY = 400f
        val dt = ms(11)
        repeat(400) { // 4.4 s to settle
            t += dt
            targetX += vxImgPerSec * (dt / 1e9f)
            c = c.copy(target = AtlasCamera.Target(targetX, targetY, 1f)).step(t, 120, 0f)
        }
        val lagImg = targetX - c.centerImageX
        val lagScreen = lagImg * c.scale
        // Steady-state lag for exp smoothing on a ramp = v * tau.
        //   50 px/sec * 0.12 s = 6 image px. Scale 1 => 6 screen px.
        assertTrue("walking lag too large: $lagScreen px", lagScreen in 4f..8f)
    }

    @Test fun drivingLagStaysBelowBound() {
        // Larger virtual image so the target can travel without hitting
        // the clamp during a 2-second drive.
        var c = AtlasCamera(
            centerImageX = 500f, centerImageY = 400f,
            scale = 1.0f,
            imageWidth = 4000, imageHeight = 800,
            screenWidth = 500, screenHeight = 400,
        ).withTarget(AtlasCamera.Target(500f, 400f, 1f))
        var t = 0L
        // 100 image-px/sec - above walking, below the clamp bite at 2 s.
        val vx = 100f
        var tx = 500f
        val dt = ms(11)
        repeat(200) {
            t += dt
            tx += vx * (dt / 1e9f)
            c = c.copy(target = AtlasCamera.Target(tx, 400f, 1f)).step(t, 120, 0f)
        }
        val lagScreen = (tx - c.centerImageX) * c.scale
        // Steady-state lag for exp smoothing on a ramp = v * tau =
        //   100 * 0.12 = 12 image px (12 screen px at scale 1).
        assertTrue("driving lag too large: $lagScreen", lagScreen in 8f..18f)
    }

    @Test fun flyToEndsExactlyOnTarget() {
        val now = 1_000_000_000L
        var c = base().startFlyTo(toX = 700f, toY = 500f, toScale = 1.5f, nowNanos = now, durationMs = 300)
        var t = now
        val dt = ms(11)
        repeat(40) {
            t += dt
            c = c.step(t, 120, 0f)
        }
        assertNull("flyTo should have finished", c.flyTo)
        assertEquals(700f, c.centerImageX, 1e-2f)
        assertEquals(500f, c.centerImageY, 1e-2f)
        assertEquals(1.5f, c.scale, 1e-2f)
    }

    @Test fun flyToHandsOffWithoutVisibleSeam() {
        val now = 0L
        var c = base().startFlyTo(toX = 600f, toY = 420f, toScale = 1.1f, nowNanos = now, durationMs = 300)
        var t = now
        val dt = ms(11)
        // Advance to just after fly-to completes.
        repeat(32) { t += dt; c = c.step(t, 120, 0f) }
        assertNull(c.flyTo)
        // Now install a stable target at the fly-to endpoint and step
        // once; motion should be well under 1 px (handoff seam).
        val handed = c.withTarget(AtlasCamera.Target(600f, 420f, 1.1f)).step(t + dt, 120, 2f)
        val dx = abs(handed.centerImageX - c.centerImageX)
        val dy = abs(handed.centerImageY - c.centerImageY)
        assertTrue("seam $dx,$dy > 1 px", dx < 1f && dy < 1f)
    }

    @Test fun flingDecaysAndStops() {
        var c = base().startFling(vxScreenPxPerSec = -500f, vyScreenPxPerSec = 0f, tauSeconds = 0.3f)
        assertNotNull(c.fling)
        var t = 0L
        val dt = ms(11)
        var moved = 0f
        val startX = c.centerImageX
        repeat(300) {
            t += dt
            val before = c.centerImageX
            c = c.step(t, 120, 0f)
            moved += abs(c.centerImageX - before)
        }
        // Fling must settle (fling becomes null) and leave the camera
        // inside bounds.
        assertNull("fling should have decayed to null", c.fling)
        assertTrue("fling should have moved the camera", moved > 1f)
        assertTrue(c.centerImageX in -c.screenWidth / 2f..(c.imageWidth + c.screenWidth / 2f))
    }

    @Test fun clampKeepsImageCoveringViewport() {
        val c = base().snapTo(100000f, -100000f, 1f)
        // At scale 1 the half-viewport is 250 x, 200 y image px.
        // Image is 1000 x 800. Centre must stay in [250, 750] x
        // [200, 600] so the Canvas never shows black on an edge.
        val halfX = c.screenWidth / (2f * c.scale)
        val halfY = c.screenHeight / (2f * c.scale)
        assertTrue(c.centerImageX >= halfX - 1e-3f)
        assertTrue(c.centerImageX <= c.imageWidth - halfX + 1e-3f)
        assertTrue(c.centerImageY >= halfY - 1e-3f)
        assertTrue(c.centerImageY <= c.imageHeight - halfY + 1e-3f)
    }

    @Test fun zoomAboutScreenFocalKeepsPointFixed() {
        val c = base()
        val focal = Pair(50f, 100f) // top-left-ish
        // Image point under focal before zoom:
        val vp = c.toViewport()
        val before = vp.screenToImage(focal.first, focal.second)
        val zoomed = c.zoomAbout(zoom = 2.0f, focalScreenX = focal.first, focalScreenY = focal.second)
        val vp2 = zoomed.toViewport()
        val after = vp2.screenToImage(focal.first, focal.second)
        assertEquals(before[0], after[0], 1e-2f)
        assertEquals(before[1], after[1], 1e-2f)
    }

    @Test fun panByClearsTargetAndFling() {
        val c = base()
            .withTarget(AtlasCamera.Target(100f, 100f, 1f))
            .startFling(50f, 0f)
            .panBy(10f, 10f)
        assertNull(c.target)
        assertNull(c.fling)
    }

    @Test fun deadZoneSuppressesSubPixelDrift() {
        // Target 1 image px away, scale 1 → 1 screen px, inside dz=2.
        var c = base().withTarget(AtlasCamera.Target(501f, 400f, 1f))
        var t = 0L
        repeat(10) { t += ms(11); c = c.step(t, 120, deadZonePx = 2f) }
        assertEquals(500f, c.centerImageX, 1e-3f)
    }

    // -- Rotation smoothing ------------------------------------------

    @Test fun rotationTargetNullFreezesRotation() {
        var c = base().snapRotation(42f)
        var t = 0L
        repeat(30) { t += ms(11); c = c.step(t) }
        assertEquals(42f, c.rotationDeg, 1e-3f)
    }

    @Test fun rotation90DegStepApproachesMonotonically() {
        var c = base().snapRotation(0f).withRotationTarget(90f)
        var t = 0L
        var last = 0f
        repeat(80) {
            t += ms(11)
            c = c.step(t, rotationDeadZoneDeg = 0f)
            assertTrue("rotation went backwards at $it: $last -> ${c.rotationDeg}",
                c.rotationDeg >= last - 1e-3f)
            last = c.rotationDeg
        }
        assertEquals(90f, c.rotationDeg, 1f)
    }

    @Test fun rotationShortestArcCrossesSeam() {
        var c = base().snapRotation(350f).withRotationTarget(10f)
        var t = 0L
        repeat(60) {
            t += ms(11)
            c = c.step(t, rotationDeadZoneDeg = 0f)
            val inShort = c.rotationDeg >= 350f || c.rotationDeg <= 15f
            assertTrue("off the short arc at step $it: ${c.rotationDeg}", inShort)
        }
        assertEquals(10f, c.rotationDeg, 1f)
    }

    @Test fun rotationShortestArcTheOtherWay() {
        var c = base().snapRotation(10f).withRotationTarget(350f)
        var t = 0L
        repeat(60) {
            t += ms(11)
            c = c.step(t, rotationDeadZoneDeg = 0f)
            val inShort = c.rotationDeg <= 10f || c.rotationDeg >= 345f
            assertTrue("off the short arc at step $it: ${c.rotationDeg}", inShort)
        }
        assertEquals(350f, c.rotationDeg, 1f)
    }

    @Test fun constantAngularVelocityLagStaysSmall() {
        var c = base().snapRotation(0f).withRotationTarget(0f)
        var t = 0L
        var targetRot = 0f
        val dt = ms(11)
        val vxPerSec = 45f
        repeat(400) {
            t += dt
            targetRot = PositionTrack.normalise360(targetRot + vxPerSec * (dt / 1e9f))
            c = c.withRotationTarget(targetRot).step(t, rotationDeadZoneDeg = 0f)
        }
        val lag = kotlin.math.abs(PositionTrack.shortestArcDeg(c.rotationDeg, targetRot))
        assertTrue("constant-vel lag too large: $lag deg", lag < 9f)
    }

    @Test fun rotationDeadZoneHoldsStill() {
        var c = base().snapRotation(0f).withRotationTarget(1f)
        var t = 0L
        repeat(20) { t += ms(11); c = c.step(t, rotationDeadZoneDeg = 1.5f) }
        assertEquals(0f, c.rotationDeg, 1e-3f)
    }

    @Test fun fastRotationTauReachesTargetSooner() {
        var fast = base().snapRotation(0f).withRotationTarget(90f)
        var slow = base().snapRotation(0f).withRotationTarget(90f)
        var t = 0L
        repeat(20) {
            t += ms(11)
            fast = fast.step(t, rotationTimeConstantMs = 180)
            slow = slow.step(t, rotationTimeConstantMs = 400)
        }
        assertTrue("fast tau did not get ahead: ${fast.rotationDeg} vs ${slow.rotationDeg}",
            fast.rotationDeg > slow.rotationDeg)
    }
}
