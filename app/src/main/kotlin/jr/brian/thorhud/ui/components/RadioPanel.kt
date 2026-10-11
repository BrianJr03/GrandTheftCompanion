package jr.brian.thorhud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState

@Composable
internal fun RadioPanel(
    s: GameState?,
    align: CardAlign = CardAlign.Start,
    modifier: Modifier = Modifier,
) {
    HudCard(modifier, stringResource(R.string.label_radio), align = align) {
        BigValue(
            labelRes = null,
            value = radioName(gameRadioIndexToEnumIndex(s?.episode, s?.radio)),
            textAlign = textAlignOf(align),
        )
    }
}

/**
 * Episode-aware radio picker.
 *
 * @param stations ordered list of enum station IDs for the current
 *                 episode. Button i displays label "i" (the episode's
 *                 local index) and taps SEND stations[i] via the Phase-3
 *                 RETUNE_RADIO_TO_STATION_INDEX command.
 * @param currentLocal game-reported local station index; the matching
 *                 button lights up in the episode accent.
 */
@Composable
internal fun RadioPicker(
    stations: IntArray,
    currentLocal: Int,
    palette: HudPalette,
    onStationTap: (Int) -> Unit,
) {
    val half = (stations.size + 1) / 2
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.label_radio_stations),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
        )
        RadioPickerRow(
            start = 0, endExclusive = half,
            stations = stations, currentLocal = currentLocal,
            palette = palette, onStationTap = onStationTap,
        )
        RadioPickerRow(
            start = half, endExclusive = stations.size,
            stations = stations, currentLocal = currentLocal,
            palette = palette, onStationTap = onStationTap,
        )
    }
}

@Composable
private fun RadioPickerRow(
    start: Int,
    endExclusive: Int,
    stations: IntArray,
    currentLocal: Int,
    palette: HudPalette,
    onStationTap: (Int) -> Unit,
) {
    // Short labels (freq where known, callsign otherwise) indexed by
    // enum station id. Button i's label = shortLabels[stations[i]].
    val shortLabels = stringArrayResource(R.array.radio_short)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (local in start until endExclusive) {
            val isCurrent = local == currentLocal
            val enumIdx = stations[local]
            val label = shortLabels.getOrNull(enumIdx) ?: local.toString()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        color = if (isCurrent) palette.accent else Color(0xFF1F2A24),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onStationTap(enumIdx) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isCurrent) Color(0xFF000000) else Color(0xFFB6CCBC),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// --- radio index-space conversion -------------------------------------
//
// GTA IV radio has two different index spaces that fooled the picker:
//   SET (RETUNE_RADIO_TO_STATION_INDEX): expects the authoritative
//       20-item enum index (0..19, see res/values/strings.xml).
//   GET (GET_PLAYER_RADIO_STATION_INDEX): returns the current episode's
//       LOCAL 0-based index (base IV / TLAD / TBoGT each skip the
//       stations they don't carry and renumber the rest).
//
// Without conversion the picker tapped "9" (The Beat enum=9), SET
// honoured it, but GET came back as local 7 and the highlight landed
// on the "7" button (The Journey — which TBoGT doesn't even have).
//
// gameRadioIndexToEnumIndex() takes GET's local return value and maps
// it back into our 20-slot picker so the highlight follows the actual
// station playing.

/**
 * Local 0..15 → enum index. Base GTA IV and TBoGT both drop the same
 * four enum stations (5 WKTT, 7 The Journey, 12 PLR, 19 Integrity 2.0)
 * and renumber the rest contiguously — derived from on-device taps on
 * both episodes. TLAD extends the same map on the assumption its skip
 * pattern matches; retract if gameplay shows otherwise. TBoGT also has
 * Self Radio at local 16 which has no enum slot (would need custom
 * audio) so it's omitted from the picker.
 */
private val IV_LOCAL_TO_ENUM = intArrayOf(
    0, 1, 2, 3, 4, 6, 8, 9, 10, 11, 13, 14, 15, 16, 17, 18,
)

/** Fallback for unknown / null episode — identity, which at least
 *  matches the enum ordering 1:1 so highlights look plausible. */
private val PASSTHROUGH_LOCAL_TO_ENUM = IntArray(20) { it }

internal fun stationListFor(episode: Int?): IntArray = when (episode) {
    0, 1, 2 -> IV_LOCAL_TO_ENUM         // IV, TLAD, TBoGT share the map
    else    -> PASSTHROUGH_LOCAL_TO_ENUM
}

internal fun gameRadioIndexToEnumIndex(episode: Int?, localIdx: Int?): Int {
    if (localIdx == null || localIdx < 0) return -1
    return stationListFor(episode).getOrElse(localIdx) { -1 }
}

@Composable
internal fun radioName(id: Int?): String {
    val table = stringArrayResource(R.array.radio_names)
    return when {
        id == null || id == -1    -> stringResource(R.string.radio_off)
        id in table.indices        -> table[id]
        else                       -> stringResource(R.string.radio_unknown, id)
    }
}
