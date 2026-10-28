package jr.brian.thorhud.ui.map.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.HeaderIconButton

/**
 * Right-edge zoom pill: `+`, the current level label, `−`.
 * Long-press on `−` goes to fit.
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ZoomOverlay(
    scale: Float,
    fitScale: Float,
    calibration: Calibration?,
    accent: Color,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
) {
    val label = ZoomPresets.labelFor(scale, fitScale, calibration)
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC000000))
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeaderIconButton(
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.atlas_zoom_in_cd),
            tint = accent,
            onClick = onZoomIn,
        )
        Text(
            text = label,
            color = Color(0xFFB6CCBC),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        // Long-press on `−` jumps to fit. detectTapGestures gives us
        // both callbacks on the same box.
        Box(
            modifier = Modifier
                .size(32.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onZoomOut() },
                        onLongPress = { onFit() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.atlas_zoom_out_cd),
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
