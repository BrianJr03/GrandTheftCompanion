package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.FullScreenSheet
import jr.brian.thorhud.ui.components.HeaderIconButton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen color picker. A real wheel: hue is the angle around
 * the center, saturation is the distance from the center (0 at
 * white, 1 at the rim). A horizontal value slider sits under the
 * wheel so black and darker shades are reachable. The current pick
 * is drawn as a small outlined ring on the wheel + a thumb on the
 * slider; the preview tile + hex readout live above both.
 *
 * [onPick] receives the packed `0x00RRGGBB` — same wire shape as a
 * swatch tap, so the plugin's paint_rgb handler runs identical
 * code. [initial] seeds the picker's HSV; null starts at a neutral
 * red so the sheet looks live on first open.
 *
 * GTA V only. The caller gates the "custom color" button on the V
 * side; IV refuses paint_rgb at the plugin with "not in this game".
 */
/** Which paint layer the wheel is currently editing. The wire format
 *  for paint_rgb uses the high byte of the packed value as a layer
 *  selector: 0 = both (legacy), 1 = primary only, 2 = secondary only. */
internal enum class PaintLayer(val wireCode: Int) {
    Primary(0x01),
    Secondary(0x02),
}

@Composable
internal fun ColorWheelSheet(
    visible: Boolean,
    accent: Color,
    initial: Long? = null,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val seed = remember(initial, visible) { initial ?: packRgb(0xB2, 0x1E, 0x1E) }
    val (sr, sg, sb) = unpackRgb(seed)
    val seedHsv = remember(seed) { rgbToHsv(sr, sg, sb) }

    // Independent HSV state per layer; the wheel edits whichever is
    // currently selected. Both seed from the same initial on open so
    // the two preview tiles start as the last color the user picked.
    var editing by remember(seed) { mutableStateOf(PaintLayer.Primary) }
    var primaryHue by remember(seed) { mutableFloatStateOf(seedHsv.first) }
    var primarySat by remember(seed) { mutableFloatStateOf(seedHsv.second) }
    var primaryValue by remember(seed) { mutableFloatStateOf(seedHsv.third) }
    var secondaryHue by remember(seed) { mutableFloatStateOf(seedHsv.first) }
    var secondarySat by remember(seed) { mutableFloatStateOf(seedHsv.second) }
    var secondaryValue by remember(seed) { mutableFloatStateOf(seedHsv.third) }

    val primaryPacked = hsvToRgbPacked(primaryHue, primarySat, primaryValue)
    val secondaryPacked = hsvToRgbPacked(secondaryHue, secondarySat, secondaryValue)
    val (ppr, ppg, ppb) = unpackRgb(primaryPacked)
    val (spr, spg, spb) = unpackRgb(secondaryPacked)
    val primaryPreview = Color(ppr, ppg, ppb)
    val secondaryPreview = Color(spr, spg, spb)

    val activeHue = if (editing == PaintLayer.Primary) primaryHue else secondaryHue
    val activeSat = if (editing == PaintLayer.Primary) primarySat else secondarySat
    val activeValue = if (editing == PaintLayer.Primary) primaryValue else secondaryValue
    val activePacked = if (editing == PaintLayer.Primary) primaryPacked else secondaryPacked

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.cheats_paint_wheel_title),
        accent = accent,
        headerActions = {
            // Save: fire paint_rgb for the active layer only. Plugin
            // decodes the high byte of the arg (layer selector) and
            // writes only that layer's custom color.
            HeaderIconButton(
                icon = Icons.Filled.Check,
                contentDescription = stringResource(R.string.cheats_paint_wheel_apply),
                tint = accent,
                onClick = {
                    val layered = activePacked or
                        (editing.wireCode.toLong() shl 24)
                    onPick(layered)
                    onDismiss()
                },
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Two previews + a switch on the right. Each preview is
            // framed in accent only while that layer is being edited,
            // so the wheel's effect at a glance is obvious. The hex
            // readout follows the active layer.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PreviewTile(
                    color = primaryPreview,
                    frame = if (editing == PaintLayer.Primary) accent
                            else accent.copy(alpha = 0.3f),
                    label = stringResource(R.string.cheats_paint_primary),
                    labelColor = accent,
                )
                PreviewTile(
                    color = secondaryPreview,
                    frame = if (editing == PaintLayer.Secondary) accent
                            else accent.copy(alpha = 0.3f),
                    label = stringResource(R.string.cheats_paint_secondary),
                    labelColor = accent,
                )
                Text(
                    text = String.format("#%06X", activePacked),
                    color = Color(0xFFEDEDED),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Toggle between primary and secondary. Switch on =
                // secondary (right-hand slot in the preview row).
                androidx.compose.material3.Switch(
                    checked = editing == PaintLayer.Secondary,
                    onCheckedChange = {
                        editing = if (it) PaintLayer.Secondary else PaintLayer.Primary
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = accent,
                        uncheckedThumbColor = accent,
                        uncheckedTrackColor = Color.Black,
                        uncheckedBorderColor = accent,
                    ),
                )
            }

            // Wheel + slider edit the active layer's state. Flipping
            // the switch above swaps which pair of HSV vars updates.
            HueSatWheel(
                hue = activeHue,
                saturation = activeSat,
                value = activeValue,
                accent = accent,
                onChange = { h, s ->
                    if (editing == PaintLayer.Primary) {
                        primaryHue = h; primarySat = s
                    } else {
                        secondaryHue = h; secondarySat = s
                    }
                },
            )
            ValueSlider(
                hue = activeHue,
                saturation = activeSat,
                value = activeValue,
                accent = accent,
                onValueChange = {
                    if (editing == PaintLayer.Primary) primaryValue = it
                    else secondaryValue = it
                },
            )
        }
    }
}

@Composable
private fun PreviewTile(
    color: Color,
    frame: Color,
    label: String,
    labelColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .border(2.dp, frame, RoundedCornerShape(8.dp)),
        )
        Text(
            text = label,
            color = labelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

/**
 * Circular HSV wheel. Hue = angle (0° at 3 o'clock, counter-
 * clockwise by Compose convention; we normalize so hue 0 reads as
 * red regardless). Saturation = radial distance 0..1 from center.
 *
 * Drawn in three layers:
 *   1. Sweep gradient around the center for pure-saturation hue.
 *   2. Radial white→transparent gradient so the center is white
 *      (saturation 0) and the rim is full hue.
 *   3. A value overlay (black at low value) so the whole wheel
 *      darkens as the value slider moves down.
 */
@Composable
private fun HueSatWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    accent: Color,
    onChange: (hue: Float, saturation: Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val radius = min(cx, cy)

        // Convert a touch position (local to the Canvas) into a
        // (hue, saturation) pair. Clamps saturation to 1 outside
        // the rim so dragging past the edge still produces a valid
        // pick instead of a dead zone.
        val pickFromOffset = { pos: Offset ->
            val dx = pos.x - cx
            val dy = pos.y - cy
            val dist = hypot(dx, dy)
            val s = (dist / radius).coerceIn(0f, 1f)
            // atan2 returns -π..π with 0 at +x. Normalize to
            // 0..360 so hue 0 is red (3 o'clock). y grows downward
            // on screen; a negated dy would mirror top/bottom, but
            // we don't bother — the wheel is radially symmetric and
            // the user picks by eye.
            val raw = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val h = (raw + 360f) % 360f
            onChange(h, s)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos -> pickFromOffset(pos) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickFromOffset(change.position)
                    }
                },
        ) {
            // Layer 1: hue sweep. Six stops cover every multiple of
            // 60 so the gradient looks continuous.
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(255, 0, 0),
                        Color(255, 255, 0),
                        Color(0, 255, 0),
                        Color(0, 255, 255),
                        Color(0, 0, 255),
                        Color(255, 0, 255),
                        Color(255, 0, 0),
                    ),
                    center = Offset(cx, cy),
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
            // Layer 2: white center → transparent rim for the
            // saturation axis.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
            // Layer 3: value overlay. A black fill with alpha
            // (1 - value) darkens the whole wheel so moving the
            // slider down produces the same colors the preview
            // tile would show.
            if (value < 1f) {
                drawCircle(
                    color = Color.Black.copy(alpha = 1f - value),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }

            // Thumb: a small ring at (hue, saturation). Polar to
            // cartesian: the touch handler used atan2(dy, dx), so
            // the inverse is cos(h) / sin(h) with dy's sign kept
            // consistent (screen-y positive going down).
            val rad = Math.toRadians(hue.toDouble())
            val r = saturation * radius
            val tx = cx + (r * cos(rad)).toFloat()
            val ty = cy + (r * sin(rad)).toFloat()
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = Offset(tx, ty),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = accent,
                radius = 9.dp.toPx(),
                center = Offset(tx, ty),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/**
 * Horizontal value slider. Left end is black (value 0), right end
 * is the currently selected hue+saturation at full value. Thumb is
 * a thin accent bar.
 */
@Composable
private fun ValueSlider(
    hue: Float,
    saturation: Float,
    value: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val (fr, fg, fb) = unpackRgb(hsvToRgbPacked(hue, saturation, 1f))
        val full = Color(fr, fg, fb)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(hue, saturation) {
                    detectTapGestures { pos ->
                        onValueChange((pos.x / widthPx).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(hue, saturation) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValueChange((change.position.x / widthPx).coerceIn(0f, 1f))
                    }
                },
        ) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Black, 1f to full),
            )
            val thumbX = value * size.width
            drawRect(
                color = accent,
                topLeft = Offset(thumbX - 1.dp.toPx(), 0f),
                size = Size(2.dp.toPx(), size.height),
            )
        }
    }
}

/** RGB → HSV. Returns (h in 0..360, s in 0..1, v in 0..1). Pure
 *  Kotlin so the seed path round-trips with [hsvToRgbPacked] under
 *  test. */
internal fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min
    val v = max
    val s = if (max == 0f) 0f else delta / max
    val h = when {
        delta == 0f -> 0f
        max == rf   -> 60f * (((gf - bf) / delta) % 6f)
        max == gf   -> 60f * (((bf - rf) / delta) + 2f)
        else        -> 60f * (((rf - gf) / delta) + 4f)
    }
    val hh = if (h < 0) h + 360f else h
    return Triple(hh, s, v)
}
