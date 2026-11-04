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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/**
 * Trainer weather picker. Grid of 14 named presets plus a "stop
 * override" chip that hands control back to the game. Tapping a chip
 * dispatches straight away; no apply button because weather is a
 * single-choice state, not a draft the user is editing.
 */
@Composable
internal fun TrainerWeatherSheet(
    visible: Boolean,
    accent: Color,
    pending: Boolean,
    error: String?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.trainer_weather_title),
        accent = accent,
    ) {
        val dim = Color(0xFF6A7A6E)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.trainer_weather_subtitle),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )

            // Two-wide grid. 14 presets chunk to 7 rows; a single
            // trailing "stock" chip rides on its own row below.
            val rows = remember { TrainerModel.WEATHER_PRESETS.chunked(2) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (preset in row) {
                            WeatherPill(
                                label = preset.label,
                                accent = accent,
                                enabled = !pending,
                                onClick = { onPick(preset.wire) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                // Trailing "stock" row: CLEAR_WEATHER_TYPE_PERSIST on
                // the plugin side. Full width so the hand-back action
                // reads as distinct from the named presets above.
                Row(modifier = Modifier.fillMaxWidth()) {
                    WeatherPill(
                        label = stringResource(R.string.common_stock),
                        accent = accent,
                        enabled = !pending,
                        onClick = { onPick(TrainerModel.WEATHER_WIRE_CLEAR) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
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
private fun WeatherPill(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, accent.copy(alpha = alpha), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = accent.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
