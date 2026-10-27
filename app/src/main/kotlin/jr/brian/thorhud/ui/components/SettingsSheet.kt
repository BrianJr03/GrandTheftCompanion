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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
 * Per-game slice of the settings sheet. Everything that writes
 * differently depending on which game is connected lives on one of
 * these; globals (plugin reconnect, snackbar) stay on the outer
 * sheet. The sheet renders one panel inline when only one is given,
 * or a tab row + HorizontalPager when both are.
 */
internal data class SettingsGamePanel(
    val label: String,
    val palette: HudPalette,
    val autoSwipeOnDrive: Boolean,
    val onAutoSwipeChange: (Boolean) -> Unit,
    val autoStartMirror: Boolean,
    val onAutoStartMirrorChange: (Boolean) -> Unit,
    val alwaysReturnToHud: Boolean,
    val onAlwaysReturnToHudChange: (Boolean) -> Unit,
    val mapControlsRight: Boolean,
    val onMapControlsRightChange: (Boolean) -> Unit,
    val autoReapplyCheats: Boolean,
    val onAutoReapplyCheatsChange: (Boolean) -> Unit,
    val onClearSavedCheats: () -> Unit,
    // Re-arm every first-visit sheet (HUD + cheats). Game-agnostic
    // in effect but surfaced on the per-game panel so the row sits
    // under the game's own accent — one tap re-opens the sheets on
    // whichever page you visit next.
    val onResetOnboarding: () -> Unit,
    // Ammu-Nation section. V-only in effect (IV has no armory
    // natives); showAmmuSection is false on the IV panel so the
    // header never appears there. autoSelectEquipped drives the
    // sheet's first-open behavior: true (default) opens on the
    // currently-equipped weapon, false opens on the first owned
    // weapon so the user can walk the list themselves.
    val showAmmuSection: Boolean = false,
    val autoSelectEquipped: Boolean = true,
    val onAutoSelectEquippedChange: (Boolean) -> Unit = {},
    // Drop-in folder: the user-pickable (SAF) folder the companion
    // syncs `<game>.png` / `<game>.json` from on refresh. Null when
    // nothing is picked yet; the fallback is the app-private files
    // dir, which the current adb-less user can't reach. [onPickFolder]
    // launches an ActivityResultLauncher the caller owns.
    val atlasFolderDisplay: String? = null,
    val onPickAtlasFolder: () -> Unit = {},
    val onClearAtlasFolder: () -> Unit = {},
)

/**
 * Full-screen settings panel with the companion's session-wide
 * toggles, grouped under expandable sections. One section open at
 * a time — tapping a different header collapses the current one
 * with an animation. Palette-driven so accents match whichever
 * game is live.
 *
 * Built on [FullScreenSheet] because the per-game panel scrolls
 * vertically; a draggable `ModalBottomSheet` would fight that
 * scroll. Dismissal is via the close icon in the header or back.
 *
 * [panels] carries one entry per game to display. Setup screen
 * (disconnected) passes both IV and V so the user can configure
 * either; in-game only passes the active game. [initialPage] is
 * the page the pager opens on when both are present. [visible]
 * drives the slide-in / slide-out animation — compose this
 * unconditionally so the exit animation plays before unmount.
 */
@Composable
internal fun SettingsSheet(
    visible: Boolean,
    panels: List<SettingsGamePanel>,
    initialPage: Int,
    cheatsClearedEvents: Flow<Unit>,
    onReconnectPlugin: () -> Unit,
    isReconnecting: Boolean,
    reconnectEvents: Flow<Boolean>,
    onDismiss: () -> Unit,
) {
    require(panels.isNotEmpty()) { "SettingsSheet needs at least one panel" }
    val coroutineScope = rememberCoroutineScope()
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
    // One expanded section at a time, shared across tabs. Panel
    // opens with every section collapsed. rememberSaveable so a
    // rotation doesn't snap everything closed after the user has
    // expanded one.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }

    val showTabs = panels.size > 1
    val safeInitial = initialPage.coerceIn(0, panels.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial) { panels.size }
    // Title / plugin section accent follows whichever page is in
    // view, so a swipe fades the accent to the other game.
    val activePanel = panels[pagerState.currentPage.coerceAtMost(panels.lastIndex)]

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_title),
        accent = activePanel.palette.accent,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showTabs) {
                GameTabRow(
                    panels = panels,
                    selectedIndex = pagerState.currentPage,
                    onSelect = { idx ->
                        coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                    },
                )
                Spacer(Modifier.height(16.dp))
            }

            // Per-game content: either a pager (both games) or the
            // single panel rendered directly. Takes the remaining
            // vertical space so the per-game column can scroll on
            // its own without pushing the plugin section off-screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (showTabs) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        PerGameSettings(
                            panel = panels[page],
                            openSection = openSection,
                            onToggleSection = { name ->
                                openSection = if (openSection == name) null else name
                            },
                        )
                    }
                } else {
                    PerGameSettings(
                        panel = panels[0],
                        openSection = openSection,
                        onToggleSection = { name ->
                            openSection = if (openSection == name) null else name
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Plugin reconnect is game-agnostic: it kicks the TCP
            // transport, not anything inside the game. Lives outside
            // the pager so it stays visible no matter which tab is up.
            Section(
                name = SECTION_PLUGIN,
                title = stringResource(R.string.settings_section_plugin),
                palette = activePanel.palette,
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
                    palette = activePanel.palette,
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
                contentColor = activePanel.palette.accent,
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

/**
 * One tappable row with every panel's label; selected tab paints
 * its own game's accent, the others are dim. Matches the sheet's
 * monospace / all-caps feel.
 */
@Composable
private fun GameTabRow(
    panels: List<SettingsGamePanel>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val dim = Color(0xFF6A7A6E)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        panels.forEachIndexed { index, panel ->
            val selected = index == selectedIndex
            val accent = if (selected) panel.palette.accent else dim
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    panel.label,
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(accent),
                )
            }
        }
    }
}

/**
 * The minimap + cheats sections for one game. Scrolls internally
 * because the parent gives this fixed vertical space (the pager
 * region) and translations / future rows can push past it.
 */
@Composable
private fun PerGameSettings(
    panel: SettingsGamePanel,
    openSection: String?,
    onToggleSection: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Section(
            name = SECTION_CHEATS,
            title = stringResource(R.string.settings_section_cheats),
            palette = panel.palette,
            expanded = openSection == SECTION_CHEATS,
            onToggle = { onToggleSection(SECTION_CHEATS) },
        ) {
            SettingRow(
                title = stringResource(R.string.settings_auto_reapply_cheats_title),
                body = stringResource(R.string.settings_auto_reapply_cheats_body),
                checked = panel.autoReapplyCheats,
                onCheckedChange = panel.onAutoReapplyCheatsChange,
                palette = panel.palette,
            )
            Spacer(Modifier.height(14.dp))
            ActionRow(
                title = stringResource(R.string.settings_clear_cheats_title),
                body = stringResource(R.string.settings_clear_cheats_body),
                buttonLabel = stringResource(R.string.settings_clear_cheats_button),
                onClick = panel.onClearSavedCheats,
                busy = false,
                palette = panel.palette,
            )
            Spacer(Modifier.height(14.dp))
            // Re-arms both first-visit onboarding sheets (HUD + cheats)
            // so they show again on the next visit. Sits here rather
            // than in its own section because the cheats sheet is the
            // feature a player is most likely to want to revisit.
            ActionRow(
                title = stringResource(R.string.settings_show_tips_title),
                body = stringResource(R.string.settings_show_tips_body),
                buttonLabel = stringResource(R.string.settings_show_tips_button),
                onClick = panel.onResetOnboarding,
                busy = false,
                palette = panel.palette,
            )
        }
        Spacer(Modifier.height(16.dp))

        Section(
            name = SECTION_MINIMAP,
            title = stringResource(R.string.settings_section_minimap),
            palette = panel.palette,
            expanded = openSection == SECTION_MINIMAP,
            onToggle = { onToggleSection(SECTION_MINIMAP) },
        ) {
            SettingRow(
                title = stringResource(R.string.settings_auto_swipe_title),
                body = stringResource(R.string.settings_auto_swipe_body),
                checked = panel.autoSwipeOnDrive,
                onCheckedChange = panel.onAutoSwipeChange,
                palette = panel.palette,
            )
            Spacer(Modifier.height(14.dp))
            SettingRow(
                title = stringResource(R.string.settings_always_return_hud_title),
                body = stringResource(R.string.settings_always_return_hud_body),
                checked = panel.alwaysReturnToHud,
                onCheckedChange = panel.onAlwaysReturnToHudChange,
                palette = panel.palette,
            )
            Spacer(Modifier.height(14.dp))
            SettingRow(
                title = stringResource(R.string.settings_auto_mirror_title),
                body = stringResource(R.string.settings_auto_mirror_body),
                checked = panel.autoStartMirror,
                onCheckedChange = panel.onAutoStartMirrorChange,
                palette = panel.palette,
            )
            Spacer(Modifier.height(14.dp))
            SettingRow(
                title = stringResource(R.string.settings_controls_side_title),
                body = stringResource(R.string.settings_controls_side_body),
                checked = panel.mapControlsRight,
                onCheckedChange = panel.onMapControlsRightChange,
                palette = panel.palette,
            )
            Spacer(Modifier.height(14.dp))
            AtlasFolderRow(
                display = panel.atlasFolderDisplay,
                onPick = panel.onPickAtlasFolder,
                onClear = panel.onClearAtlasFolder,
                palette = panel.palette,
            )
        }

        if (panel.showAmmuSection) {
            Spacer(Modifier.height(16.dp))
            Section(
                name = SECTION_AMMU,
                title = stringResource(R.string.settings_section_ammu),
                palette = panel.palette,
                expanded = openSection == SECTION_AMMU,
                onToggle = { onToggleSection(SECTION_AMMU) },
            ) {
                SettingRow(
                    title = stringResource(
                        R.string.settings_ammu_auto_select_title),
                    body = stringResource(
                        R.string.settings_ammu_auto_select_body),
                    checked = panel.autoSelectEquipped,
                    onCheckedChange = panel.onAutoSelectEquippedChange,
                    palette = panel.palette,
                )
            }
        }
    }
}

private const val SECTION_MINIMAP = "minimap"
private const val SECTION_CHEATS = "cheats"
private const val SECTION_PLUGIN = "plugin"
private const val SECTION_AMMU = "ammu"

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
private fun AtlasFolderRow(
    display: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    palette: HudPalette,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_atlas_folder_title),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_atlas_folder_body),
            color = Color(0xFF8EA096),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            display ?: stringResource(R.string.settings_atlas_folder_none),
            color = if (display == null) Color(0xFF6A7A6E) else palette.accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    stringResource(
                        if (display == null) R.string.settings_atlas_folder_pick
                        else R.string.settings_atlas_folder_change,
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            if (display != null) {
                Button(
                    onClick = onClear,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1F2A24),
                        contentColor = Color(0xFFEDEDED),
                    ),
                ) {
                    Text(
                        stringResource(R.string.settings_atlas_folder_clear),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
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
