package jr.brian.thorhud.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R

@Composable
internal fun SetupScreen(lastError: String?) {
    var showInfo by remember { mutableStateOf(false) }

    Scaffold(containerColor = Color.Black) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    stringResource(R.string.setup_title),
                    color = PaletteIV.accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.setup_subtitle),
                    color = Color(0xFF8EA096),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.setup_section),
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                ChecklistItem("1", R.string.setup_step1_title, R.string.setup_step1_detail)
                ChecklistItem("2", R.string.setup_step2_title, R.string.setup_step2_detail)
                ChecklistItem("3", R.string.setup_step3_title, R.string.setup_step3_detail)
                ChecklistItem("4", R.string.setup_step4_title, R.string.setup_step4_detail)
            }
            // Info chip at the top-right. Tap for the last connection
            // error — tinted red when there's actually an error to show,
            // grey otherwise.
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.info_icon_cd),
                    tint = if (lastError.isNullOrBlank()) Color(0xFF6A7A6E)
                           else Color(0xFFB04040),
                )
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = Color.Black,
            titleContentColor = PaletteIV.accent,
            textContentColor = Color(0xFFEDEDED),
            title = {
                Text(
                    stringResource(R.string.info_dialog_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    if (lastError.isNullOrBlank())
                        stringResource(R.string.info_dialog_no_error)
                    else
                        stringResource(R.string.info_dialog_last_error, lastError),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(
                        stringResource(R.string.info_dialog_dismiss),
                        color = PaletteIV.accent,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
        )
    }
}

@Composable
private fun ChecklistItem(
    step: String,
    @StringRes titleRes: Int,
    @StringRes detailRes: Int,
) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF1F2A24), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                step,
                color = PaletteIV.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Column {
            Text(
                stringResource(titleRes),
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(detailRes),
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
