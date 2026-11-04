package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.FullScreenSheet
import kotlin.math.roundToInt

/**
 * Trainer time-of-day picker. One slider, one apply button. The slider
 * writes nothing on drag; the apply button sends the current hour
 * through `set_time`. Separate sheet from the weather picker so each
 * stays small enough to read at a glance on the 540 x 468 dp panel.
 *
 * Compose unconditionally with [visible] driving whether the sheet is
 * on screen, so the slide-out animation plays before the content is
 * removed and the slider's picked value survives a close + reopen.
 */
@Composable
internal fun TrainerTimeSheet(
    visible: Boolean,
    accent: Color,
    pending: Boolean,
    error: String?,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by rememberSaveable { mutableIntStateOf(12) }
    // Separate float state so the slider's intermediate drag position
    // reads smoothly without quantizing every pixel; the int hoists
    // out on each value-change for the readout + apply.
    var sliderValue by rememberSaveable { mutableFloatStateOf(12f) }
    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.trainer_time_title),
        accent = accent,
    ) {
        val dim = Color(0xFF6A7A6E)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.trainer_time_subtitle),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )

            // Big H:MM readout. MM stays :00 because the slider is
            // hour-granularity; the plugin writes 0 for minutes and
            // seconds. Keeps the layout stable if we ever add a
            // minute slider in a later iteration.
            val hourLabel = "%02d:00".format(hour)
            Text(
                hourLabel,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )

            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    hour = it.roundToInt()
                                 .coerceIn(TrainerModel.TIME_MIN_HOUR,
                                           TrainerModel.TIME_MAX_HOUR)
                },
                valueRange = TrainerModel.TIME_MIN_HOUR.toFloat()..
                             TrainerModel.TIME_MAX_HOUR.toFloat(),
                steps = TrainerModel.TIME_MAX_HOUR - TrainerModel.TIME_MIN_HOUR - 1,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = accent.copy(alpha = 0.3f),
                    activeTickColor = accent.copy(alpha = 0.5f),
                    inactiveTickColor = accent.copy(alpha = 0.2f),
                ),
            )

            // Row of six preset chips for the common hours trainers
            // keep around (midnight, dawn, morning, noon, dusk, night).
            // Tapping one snaps the slider.
            val quickHours = listOf(0, 6, 9, 12, 18, 21)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (h in quickHours) {
                    QuickHourChip(
                        hour = h,
                        selected = h == hour,
                        accent = accent,
                        onClick = {
                            hour = h
                            sliderValue = h.toFloat()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            ApplyPill(
                label = stringResource(R.string.trainer_apply),
                accent = accent,
                enabled = !pending,
                onClick = { onApply(hour) },
            )
            if (!error.isNullOrBlank()) {
                Text(
                    stringResource(R.string.common_error_line, error),
                    color = Color(0xFFE07070),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun QuickHourChip(
    hour: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) accent else Color.Black
    val fg = if (selected) Color.Black else accent
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, accent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "%02d".format(hour),
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun ApplyPill(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val shape = RoundedCornerShape(50)
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(accent.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.Black,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
