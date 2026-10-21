package jr.brian.thorhud.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * Full-height bottom sheet with the companion's session-wide
 * toggles, grouped under expandable sections. One section open at a
 * time — tapping a different header collapses the current one with
 * an animation. Palette-driven so accents match whichever game is
 * live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    palette: HudPalette,
    autoSwipeOnDrive: Boolean,
    onAutoSwipeChange: (Boolean) -> Unit,
    autoStartMirror: Boolean,
    onAutoStartMirrorChange: (Boolean) -> Unit,
    alwaysReturnToHud: Boolean,
    onAlwaysReturnToHudChange: (Boolean) -> Unit,
    mapControlsRight: Boolean,
    onMapControlsRightChange: (Boolean) -> Unit,
    autoReapplyCheats: Boolean,
    onAutoReapplyCheatsChange: (Boolean) -> Unit,
    onClearSavedCheats: () -> Unit,
    cheatsClearedEvents: Flow<Unit>,
    onReconnectPlugin: () -> Unit,
    isReconnecting: Boolean,
    reconnectEvents: Flow<Boolean>,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded },
    )
    val snackbarHost = remember { SnackbarHostState() }
    val connectedMsg = stringResource(R.string.snackbar_reconnect_success)
    val failedMsg = stringResource(R.string.snackbar_reconnect_failed)
    LaunchedEffect(Unit) {
        reconnectEvents.collect { ok ->
            snackbarHost.showSnackbar(
                message = if (ok) connectedMsg else failedMsg,
                duration = SnackbarDuration.Short,
            )
        }
    }
    val cheatsClearedMsg = stringResource(R.string.snackbar_cheats_cleared)
    LaunchedEffect(Unit) {
        cheatsClearedEvents.collect {
            snackbarHost.showSnackbar(
                message = cheatsClearedMsg,
                duration = SnackbarDuration.Short,
            )
        }
    }
    // One expanded section at a time. Sheet opens with every section
    // collapsed. rememberSaveable so a rotation doesn't snap everything
    // closed after the user has expanded one.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color.Black,
        dragHandle = null,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                color = palette.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(16.dp))

            Section(
                name = SECTION_MINIMAP,
                title = stringResource(R.string.settings_section_minimap),
                palette = palette,
                expanded = openSection == SECTION_MINIMAP,
                onToggle = {
                    openSection = if (openSection == SECTION_MINIMAP) null
                                  else SECTION_MINIMAP
                },
            ) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_swipe_title),
                    body = stringResource(R.string.settings_auto_swipe_body),
                    checked = autoSwipeOnDrive,
                    onCheckedChange = onAutoSwipeChange,
                    palette = palette,
                )
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    title = stringResource(R.string.settings_always_return_hud_title),
                    body = stringResource(R.string.settings_always_return_hud_body),
                    checked = alwaysReturnToHud,
                    onCheckedChange = onAlwaysReturnToHudChange,
                    palette = palette,
                )
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    title = stringResource(R.string.settings_auto_mirror_title),
                    body = stringResource(R.string.settings_auto_mirror_body),
                    checked = autoStartMirror,
                    onCheckedChange = onAutoStartMirrorChange,
                    palette = palette,
                )
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    title = stringResource(R.string.settings_controls_side_title),
                    body = stringResource(R.string.settings_controls_side_body),
                    checked = mapControlsRight,
                    onCheckedChange = onMapControlsRightChange,
                    palette = palette,
                )
            }
            Spacer(Modifier.height(16.dp))

            Section(
                name = SECTION_CHEATS,
                title = stringResource(R.string.settings_section_cheats),
                palette = palette,
                expanded = openSection == SECTION_CHEATS,
                onToggle = {
                    openSection = if (openSection == SECTION_CHEATS) null
                                  else SECTION_CHEATS
                },
            ) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_reapply_cheats_title),
                    body = stringResource(R.string.settings_auto_reapply_cheats_body),
                    checked = autoReapplyCheats,
                    onCheckedChange = onAutoReapplyCheatsChange,
                    palette = palette,
                )
                Spacer(Modifier.height(14.dp))
                ActionRow(
                    title = stringResource(R.string.settings_clear_cheats_title),
                    body = stringResource(R.string.settings_clear_cheats_body),
                    buttonLabel = stringResource(R.string.settings_clear_cheats_button),
                    onClick = onClearSavedCheats,
                    busy = false,
                    palette = palette,
                )
            }
            Spacer(Modifier.height(16.dp))

            Section(
                name = SECTION_PLUGIN,
                title = stringResource(R.string.settings_section_plugin),
                palette = palette,
                expanded = openSection == SECTION_PLUGIN,
                onToggle = {
                    openSection = if (openSection == SECTION_PLUGIN) null
                                  else SECTION_PLUGIN
                },
            ) {
                ActionRow(
                    title = stringResource(R.string.settings_reconnect_title),
                    body = stringResource(R.string.settings_reconnect_body),
                    buttonLabel = stringResource(R.string.settings_reconnect_button),
                    onClick = onReconnectPlugin,
                    busy = isReconnecting,
                    palette = palette,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        ) { data ->
            Snackbar(
                containerColor = Color.Black,
                contentColor = palette.accent,
            ) {
                Text(
                    data.visuals.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
      }
    }
}

private const val SECTION_MINIMAP = "minimap"
private const val SECTION_CHEATS = "cheats"
private const val SECTION_PLUGIN = "plugin"

/**
 * One collapsible section inside the settings sheet. Header is a
 * tappable row with a chevron that flips when the section opens;
 * body fades/expands in from the top.
 */
@Composable
private fun Section(
    name: String,
    title: String,
    palette: HudPalette,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val text = Color(0xFFEDEDED)
    val dim = Color(0xFF6A7A6E)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = palette.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                              else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = dim,
                modifier = Modifier.width(24.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
            ) {
                content()
            }
        }
    }
    // Name is referenced by callers for the open-section id; kept
    // as a param so this stays reusable if the sheet grows more
    // sections later.
    @Suppress("UNUSED_PARAMETER") name
}

@Composable
private fun SettingRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: HudPalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = Color(0xFF4A574F),
                uncheckedTrackColor = Color(0xFF1F2A24),
            ),
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
    busy: Boolean,
    palette: HudPalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = onClick,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = Color.Black,
                disabledContainerColor = palette.accent,
                disabledContentColor = Color.Black,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    buttonLabel,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
