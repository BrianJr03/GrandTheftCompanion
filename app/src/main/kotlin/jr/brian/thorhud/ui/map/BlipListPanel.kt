package jr.brian.thorhud.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R

/**
 * The map's blips, nearest first, each with a "route" button. Tapping
 * one makes the game route to it (GTA V: sets the waypoint; GTA IV:
 * turns the blip's GPS route on). The routed one is highlighted from
 * the state frame, not from the tap, so it only lights up when the
 * game confirmed it.
 *
 * Provisional: every blip is listed with its raw sprite id. Once the
 * mission sprites are confirmed on-device this becomes a mission list
 * and the rest moves behind a filter.
 */
@Composable
internal fun BlipListPanel(
    rows: List<BlipRow>,
    accent: Color,
    onRoute: (Int) -> Unit,
    onClear: () -> Unit,
    onLogCensus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dim = Color(0xFF6A7A6E)
    val text = Color(0xFFB6CCBC)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.map_blips_title),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    if (rows.isEmpty()) stringResource(R.string.map_blips_none)
                    else stringResource(R.string.map_blips_count, rows.size),
                    color = dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            SmallButton(stringResource(R.string.map_blips_clear), accent, onClear)
            Spacer(Modifier.width(8.dp))
            SmallButton(stringResource(R.string.map_blips_log), accent, onLogCensus)
            Spacer(Modifier.width(8.dp))
            SmallButton(stringResource(R.string.map_blips_close), accent, onClose)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (row in rows) {
                val b = row.blip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (row.routed) Color(0xFF26352C) else Color(0xFF161D19))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${row.label.name} · ${row.distance}",
                            color = if (row.label.missionStart) accent else text,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(
                                R.string.map_blips_meta,
                                b.id, b.sprite, b.colour, b.blipType,
                            ),
                            color = dim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    SmallButton(
                        label = stringResource(
                            if (row.routed) R.string.map_blips_routed else R.string.map_blips_route,
                        ),
                        accent = accent,
                        highlighted = row.routed,
                        onClick = { onRoute(b.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) accent else Color(0xFF1F2A24))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) Color(0xFF000000) else Color(0xFFB6CCBC),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
