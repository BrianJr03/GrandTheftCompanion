package jr.brian.thorhud.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawOutline as drawOutlineScope

/**
 * One-shot discovery ring overlay. Draws a rotating accent-gradient
 * stroke around the content's shape at `progress` ∈ [0, 1], fading in
 * over the first [FADE_FRACTION] of the window and out over the last
 * [FADE_FRACTION] so the ring never pops. Progress at or below 0
 * draws nothing, so the usual pattern is `drawBehind` driven by an
 * `Animatable(0f).animateTo(1f)` from the caller.
 *
 * [shape] is clipped-outlined with the ring. The tab-arrow footer
 * passes `CircleShape`; the HUD card labels pass a pill
 * `RoundedCornerShape`. The caller supplies the shape so the ring
 * hugs the content it's advertising, not the invisible touch area.
 *
 * The ring draws BEHIND the content so a label's text sits on top of
 * the gradient. Use with `Modifier.drawBehind`-adjacent order: pass
 * this modifier before the content modifier, so Compose paints the
 * ring and then the content inside it.
 */
fun Modifier.discoveryRing(
    progress: Float,
    accent: Color,
    shape: Shape,
): Modifier = this.drawBehind {
    val alpha = ringAlphaFor(progress)
    if (alpha <= 0f) return@drawBehind
    val rotation = progress * HINT_ROTATION_DEG
    val strokePx = STROKE_DP * density
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.sweepGradient(
        colorStops = arrayOf(
            0.00f to accent.copy(alpha = 0f),
            0.25f to accent.copy(alpha = alpha),
            0.50f to accent.copy(alpha = alpha * 0.8f),
            0.75f to accent.copy(alpha = alpha),
            1.00f to accent.copy(alpha = 0f),
        ),
        center = center,
    )
    // Rotate at the canvas level because Compose has no "rotate a
    // brush" primitive; stroking an outline with a sweep gradient on
    // a rotating transform is the cheapest "flowing" look.
    rotate(degrees = rotation, pivot = center) {
        drawOutlineScope(
            outline = outline,
            brush = brush,
            style = Stroke(width = strokePx),
        )
    }
}

/** How long one play of the ring takes. 1.5 s matches the arrows'
 *  original timing from TabArrowFooter and reads as a one-shot. */
const val DISCOVERY_RING_DURATION_MS = 1500

/** Total degrees the ring sweeps across its window. ~1.5 turns reads
 *  as a flowing loop without feeling frantic. */
const val HINT_ROTATION_DEG = 540f

/** Fraction of the window spent fading in / out at each end. */
private const val FADE_FRACTION = 0.2f

/** Stroke width of the ring in dp. */
private const val STROKE_DP = 3f

/** Linear-fade-in-fade-out alpha curve. Public so the arrow buttons
 *  (which also inherit this via drawBehind) can share the exact same
 *  shape and the two rings pulse together. */
fun ringAlphaFor(progress: Float): Float = when {
    progress <= 0f           -> 0f
    progress < FADE_FRACTION -> progress / FADE_FRACTION
    progress > 1f - FADE_FRACTION -> (1f - progress) / FADE_FRACTION
    else                     -> 1f
}

/**
 * Convenience for drawing the ring on a circular tap target. Avoids
 * a repeated `discoveryRing(..., CircleShape)` call in the arrow
 * footer; semantically identical to passing `CircleShape` explicitly.
 */
fun Modifier.discoveryRingCircle(
    progress: Float,
    accent: Color,
): Modifier = this.discoveryRing(
    progress = progress,
    accent = accent,
    shape = CircleShape,
)
