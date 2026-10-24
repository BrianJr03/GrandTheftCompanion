package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/**
 * Full-screen HSV color picker. Composed unconditionally with
 * [visible] so the slide-out animation plays. [onPick] receives the
 * packed `0x00RRGGBB` value — identical wire format to the swatch
 * row (and the plugin's paint_rgb handler), so a tap here goes
 * through the exact same code path as a swatch tap. [initial] seeds
 * the picker with a specific colour; null starts at a neutral red.
 *
 * The picker shows a horizontal hue slider on top and a
 * saturation/value plane below. Hue is in degrees; saturation and
 * value are in 0..1. Picked value is converted via [hsvToRgbPacked]
 * so the UI never leans on Android's java.awt-shaped Color helpers.
 *
 * GTA V only — IV refuses paint_rgb with "not in this game", so the
 * caller gates the "custom colour" button on `isGtaV`.
 */
@Composable
internal fun ColorWheelSheet(
    visible: Boolean,
    accent: Color,
    initial: Long? = null,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    // HSV state. Seeded from `initial` once per visibility cycle so
    // reopening the sheet with the last-picked colour feels natural.
    val seed = remember(initial, visible) { initial ?: packRgb(0xB2, 0x1E, 0x1E) }
    val (sr, sg, sb) = unpackRgb(seed)
    val seedHsv = remember(seed) { rgbToHsv(sr, sg, sb) }

    var hue by remember(seed) { mutableFloatStateOf(seedHsv.first) }
    var sat by remember(seed) { mutableFloatStateOf(seedHsv.second) }
    var value by remember(seed) { mutableFloatStateOf(seedHsv.third) }

    val packed = hsvToRgbPacked(hue, sat, value)
    val (pr, pg, pb) = unpackRgb(packed)
    val preview = Color(pr, pg, pb)

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.cheats_paint_wheel_title),
        accent = accent,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Preview + hex: lets the user eyeball the colour before
            // committing. The hex matches what the plugin's log line
            // writes ("rgb=#RRGGBB -> ok") so a device-log check is a
            // visual compare.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(preview)
                        .border(1.dp, accent, RoundedCornerShape(8.dp)),
                )
                androidx.compose.material3.Text(
                    text = String.format("#%06X", packed),
                    color = Color(0xFFEDEDED),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Hue slider (horizontal rainbow with a draggable thumb).
            HueSlider(
                hue = hue,
                accent = accent,
                onHueChange = { hue = it },
            )

            // Saturation / value plane. Full saturation at right, full
            // value at top; the thumb is a small outlined circle.
            SvPlane(
                hue = hue,
                saturation = sat,
                value = value,
                accent = accent,
                onChange = { s, v -> sat = s; value = v },
            )

            Spacer(Modifier.height(2.dp))

            // Confirm. Dismisses the sheet; the ack path treats the
            // commit identically to a swatch tap.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent)
                    .clickable {
                        onPick(packed)
                        onDismiss()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.cheats_paint_wheel_apply),
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HueSlider(
    hue: Float,
    accent: Color,
    onHueChange: (Float) -> Unit,
) {
    // Height is a visible band; width is whatever the parent gives.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        onHueChange((pos.x / widthPx).coerceIn(0f, 1f) * 360f)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onHueChange((change.position.x / widthPx).coerceIn(0f, 1f) * 360f)
                    }
                },
        ) {
            // Rainbow brush. Six stops hit every hue multiple of 60.
            drawRect(
                brush = Brush.horizontalGradient(
                    0.0f  to Color(255, 0, 0),
                    0.167f to Color(255, 255, 0),
                    0.333f to Color(0, 255, 0),
                    0.5f   to Color(0, 255, 255),
                    0.667f to Color(0, 0, 255),
                    0.833f to Color(255, 0, 255),
                    1.0f   to Color(255, 0, 0),
                ),
            )
            // Thumb: a 2 dp vertical line at the hue's x.
            val thumbX = (hue / 360f) * size.width
            drawRect(
                color = accent,
                topLeft = Offset(thumbX - 1.dp.toPx(), 0f),
                size = Size(2.dp.toPx(), size.height),
            )
        }
    }
}

@Composable
private fun SvPlane(
    hue: Float,
    saturation: Float,
    value: Float,
    accent: Color,
    onChange: (sat: Float, value: Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        onChange(
                            (pos.x / widthPx).coerceIn(0f, 1f),
                            1f - (pos.y / heightPx).coerceIn(0f, 1f),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onChange(
                            (change.position.x / widthPx).coerceIn(0f, 1f),
                            1f - (change.position.y / heightPx).coerceIn(0f, 1f),
                        )
                    }
                },
        ) {
            // Base: white → hue at full-sat. Second layer: transparent
            // → black top-to-bottom. Stacking is the standard HSV plane
            // trick; cheaper than per-pixel HSV→RGB and visually
            // identical.
            val (hr, hg, hb) = unpackRgb(hsvToRgbPacked(hue, 1f, 1f))
            val hueColor = Color(hr, hg, hb)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to hueColor,
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                ),
            )
            // Thumb: a small ring centred on (sat, 1 - value).
            val cx = saturation * size.width
            val cy = (1f - value) * size.height
            drawCircle(
                color = accent,
                radius = 7.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
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
