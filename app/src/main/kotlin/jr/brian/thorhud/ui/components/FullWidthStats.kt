package jr.brian.thorhud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState

@Composable
internal fun FullWidthStats(s: GameState?, palette: HudPalette) {
    val v = s?.vehicle?.takeIf { s.inVehicle }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
    ) {
        Bar(label = stringResource(R.string.label_health),
            current = s?.health ?: 0f, max = 200f,
            fill = palette.health)
        Spacer(Modifier.height(6.dp))
        // Armor + engine share a single row when the player is in a
        // vehicle (half-width each, 8dp gap). On foot, armor stays
        // full-width since there's no engine to pair it with.
        if (v != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Bar(label = stringResource(R.string.label_armor),
                    current = s?.armor?.toFloat() ?: 0f,
                    max = (s?.maxArmor ?: 100).coerceAtLeast(100).toFloat(),
                    fill = palette.armor,
                    modifier = Modifier.weight(1f))
                Bar(label = stringResource(R.string.label_engine),
                    current = v.engineHp.coerceAtLeast(0f),
                    max = 1000f,
                    fill = palette.engine,
                    modifier = Modifier.weight(1f))
            }
        } else {
            Bar(label = stringResource(R.string.label_armor),
                current = s?.armor?.toFloat() ?: 0f,
                max = (s?.maxArmor ?: 100).coerceAtLeast(100).toFloat(),
                fill = palette.armor)
        }
    }
}

// internal (not private): the GTA V stats row in ui/v/ reuses it.
// `valueText` overrides the default "current/max" readout for bars
// that show a state rather than a number.
@Composable
internal fun Bar(
    label: String,
    current: Float,
    max: Float,
    fill: Color,
    modifier: Modifier = Modifier,
    valueText: String = "${current.toInt()}/${max.toInt()}",
) {
    val pct = if (max > 0) (current / max).coerceIn(0f, 1f) else 0f
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color(0xFF6A7A6E),
                 fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(valueText,
                 color = Color(0xFFB6CCBC),
                 fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color(0xFF263029), RoundedCornerShape(5.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(10.dp)
                    .background(fill, RoundedCornerShape(5.dp)),
            )
        }
    }
}
