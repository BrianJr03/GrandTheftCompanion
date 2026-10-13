package jr.brian.thorhud.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R

@Composable
internal fun SetupScreen(lastError: String?, onOpenSettings: (() -> Unit)? = null) {
    var showInfo by remember { mutableStateOf(false) }
    // Accordion state: null = both collapsed. rememberSaveable so a
    // rotation / process-death doesn't close whatever the user opened.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }

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
                SetupSection(
                    titleRes = R.string.setup_section,
                    expanded = openSection == SECTION_IV,
                    onToggle = {
                        openSection = if (openSection == SECTION_IV) null else SECTION_IV
                    },
                ) {
                    ChecklistItem("1", R.string.setup_step1_title, R.string.setup_step1_detail)
                    ChecklistItem("2", R.string.setup_step2_title, R.string.setup_step2_detail)
                    ChecklistItem("3", R.string.setup_step3_title, R.string.setup_step3_detail)
                    ChecklistItem("4", R.string.setup_step4_title, R.string.setup_step4_detail)
                }
                Spacer(Modifier.height(8.dp))
                SetupSection(
                    titleRes = R.string.setup_v_section,
                    expanded = openSection == SECTION_V,
                    onToggle = {
                        openSection = if (openSection == SECTION_V) null else SECTION_V
                    },
                ) {
                    ChecklistItem("1", R.string.setup_v_step1_title, R.string.setup_v_step1_detail)
                    ChecklistItem("2", R.string.setup_v_step2_title, R.string.setup_v_step2_detail)
                    ChecklistItem("3", R.string.setup_v_step3_title, R.string.setup_v_step3_detail)
                    ChecklistItem("4", R.string.setup_v_step4_title, R.string.setup_v_step4_detail)
                }
            }
            // Top-right chip row: settings gear (if the caller wired
            // one) and the info icon that opens the last connection
            // error. Info stays red when there is something to show.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            ) {
                onOpenSettings?.let { open ->
                    IconButton(onClick = open) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_icon_cd),
                            tint = Color(0xFF6A7A6E),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.info_icon_cd),
                        tint = if (lastError.isNullOrBlank()) Color(0xFF6A7A6E)
                               else Color(0xFFB04040),
                    )
                }
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

private const val SECTION_IV = "iv"
private const val SECTION_V = "v"

/**
 * Expandable setup section. Header is the "gta iv setup" / "gta v setup"
 * label plus a chevron that flips when the body opens; body fades /
 * expands in from the top with the checklist items. Accordion behaviour
 * is owned by the caller via [expanded] + [onToggle].
 */
@Composable
private fun SetupSection(
    @StringRes titleRes: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(titleRes),
                color = Color(0xFF6A7A6E),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                              else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF6A7A6E),
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                content()
            }
        }
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
