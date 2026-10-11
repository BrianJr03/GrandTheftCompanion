package jr.brian.thorhud.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState

@Composable
internal fun VehiclePanel(
    s: GameState?,
    palette: HudPalette,
    align: CardAlign = CardAlign.End,
    modifier: Modifier = Modifier,
) {
    val textAlign = textAlignOf(align)
    HudCard(modifier, stringResource(R.string.panel_vehicle), align = align) {
        val v = s?.vehicle?.takeIf { s.inVehicle }
        if (v == null) {
            Text(stringResource(R.string.on_foot),
                 color = Color(0xFF6A7A6E), fontSize = 20.sp,
                 textAlign = textAlign,
                 modifier = Modifier.fillMaxWidth())
            return@HudCard
        }
        Text(
            v.name.ifBlank {
                stringResource(R.string.model_fallback, v.model.toInt())
            },
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.label_speed),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        val mph = v.speedMps * 2.237f
        Text(
            stringResource(R.string.mph_value, mph.toInt()),
            fontFamily = FontFamily.Monospace, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, color = Color(0xFFEDEDED),
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
