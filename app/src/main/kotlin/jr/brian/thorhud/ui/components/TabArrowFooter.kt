package jr.brian.thorhud.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jr.brian.thorhud.R
import kotlinx.coroutines.launch

/**
 * Icon style for the sticky tab-nav buttons. User-pickable in the App
 * section of Settings.
 *
 * [Transparent] renders no glyph at all — just an invisible tap target
 * tucked against each panel edge. The first-visit hint animation
 * flashes a gradient ring around both targets so they are discoverable
 * even without an icon.
 */
enum class TabArrowIconStyle { Arrows, Stars, Transparent }

/**
 * Sticky footer with two tab-nav buttons for one-tap tab moves. Left
 * sits flush against the panel's left edge (with padding), right
 * against the right edge. Each button is a 44 dp circle; its glyph
 * depends on [style] and the backdrop drops out entirely when
 * [TabArrowIconStyle.Transparent] is chosen.
 *
 * [showHint] plays a one-shot 1.5 s gradient ring around both buttons
 * so a transparent-style user can see where the tap targets live on
 * first boot. [onHintShown] fires when the animation ends; the caller
 * persists the "seen" flag so the ring never auto-replays.
 *
 * The pager is the authority — this just calls animateScrollToPage on
 * the state the caller passes. Hidden entirely by the caller when the
 * App setting is off, so there is no on/off state inside this file.
 */
@Composable
internal fun TabArrowFooter(
    pagerState: PagerState,
    accent: Color,
    style: TabArrowIconStyle = TabArrowIconStyle.Arrows,
    showHint: Boolean = false,
    onHintShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val canPrev = pagerState.currentPage > 0
    val canNext = pagerState.currentPage < pagerState.pageCount - 1

    // One-shot ring animation. 0 → 1 over 1.5 s with linear easing so
    // the gradient's rotation feels like a steady flow rather than
    // easing to a halt. The caller flips [showHint] back to false once
    // the "seen" flag persists, which lets the Animatable snap back to
    // 0 for any future replay.
    val hintProgress = remember { Animatable(0f) }
    LaunchedEffect(showHint) {
        if (showHint) {
            hintProgress.snapTo(0f)
            hintProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = HINT_DURATION_MS, easing = LinearEasing),
            )
            onHintShown()
        } else {
            hintProgress.snapTo(0f)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ArrowButton(
            icon = iconFor(style, forward = false),
            contentDescription = stringResource(R.string.tab_arrows_prev_cd),
            style = style,
            accent = accent,
            tint = accent,
            enabled = canPrev,
            hintProgress = hintProgress.value,
            onClick = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            },
        )
        ArrowButton(
            icon = iconFor(style, forward = true),
            contentDescription = stringResource(R.string.tab_arrows_next_cd),
            style = style,
            accent = accent,
            tint = accent,
            enabled = canNext,
            hintProgress = hintProgress.value,
            onClick = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
        )
    }
}

private fun iconFor(style: TabArrowIconStyle, forward: Boolean): ImageVector? = when (style) {
    TabArrowIconStyle.Arrows -> if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight
                                else Icons.AutoMirrored.Filled.KeyboardArrowLeft
    TabArrowIconStyle.Stars -> Icons.Filled.Star
    TabArrowIconStyle.Transparent -> null
}

@Composable
private fun ArrowButton(
    icon: ImageVector?,
    contentDescription: String,
    style: TabArrowIconStyle,
    accent: Color,
    tint: Color,
    enabled: Boolean,
    hintProgress: Float,
    onClick: () -> Unit,
) {
    // Backdrop: translucent black for icon styles so the glyph reads
    // over any tab content; fully transparent for the transparent
    // style since the whole point is to disappear.
    val backdrop = if (style == TabArrowIconStyle.Transparent) {
        Color.Transparent
    } else {
        Color.Black.copy(alpha = 0.55f)
    }
    // Fade the ring in over the first 20 % and out over the last 20 %
    // of the window so it never pops into or out of view.
    val ringAlpha = when {
        hintProgress <= 0f -> 0f
        hintProgress < 0.2f -> hintProgress / 0.2f
        hintProgress > 0.8f -> (1f - hintProgress) / 0.2f
        else -> 1f
    }
    val ringRotation = hintProgress * HINT_ROTATION_DEG

    Box(
        modifier = Modifier
            .size(44.dp)
            // At pager extremes (first / last tab) the matching button
            // has nothing to scroll to. Rather than greying it out, drop
            // it to fully transparent so the HUD stays uncluttered.
            // Alpha goes through drawing so the hint ring, backdrop and
            // glyph all vanish together.
            .alpha(if (enabled) 1f else 0f)
            .clip(CircleShape)
            .background(backdrop)
            .drawBehind {
                if (ringAlpha > 0f) {
                    val strokePx = 3.dp.toPx()
                    val radius = (size.minDimension - strokePx) / 2f
                    // Rotate the whole canvas so the gradient brush
                    // rotates with the stroke — Compose has no built-in
                    // "rotate a brush" primitive.
                    rotate(degrees = ringRotation, pivot = center) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colorStops = arrayOf(
                                    0.00f to accent.copy(alpha = 0f),
                                    0.25f to accent.copy(alpha = ringAlpha),
                                    0.50f to accent.copy(alpha = ringAlpha * 0.8f),
                                    0.75f to accent.copy(alpha = ringAlpha),
                                    1.00f to accent.copy(alpha = 0f),
                                ),
                                center = center,
                            ),
                            radius = radius,
                            style = Stroke(width = strokePx),
                            center = center,
                        )
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** How long the first-visit discovery ring glows for. */
private const val HINT_DURATION_MS = 1500

/** Total degrees the ring sweeps across the hint window. ~1.5 turns
 *  reads as a flowing loop without feeling frantic. */
private const val HINT_ROTATION_DEG = 540f
