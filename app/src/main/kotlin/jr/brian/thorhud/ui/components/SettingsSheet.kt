package jr.brian.thorhud.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import jr.brian.thorhud.ui.map.custom.FollowSmoothing
import kotlinx.coroutines.flow.Flow

/**
 * Per-game slice of the settings sheet. Everything that writes
 * differently depending on which game is connected lives on one of
 * these; globals (plugin reconnect, snackbar) stay on the outer
 * sheet. The sheet renders one rail group per panel plus a trailing
 * "App" group for game-agnostic rows.
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
    // chip never appears there. autoSelectEquipped drives the
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
    // Atlas follow + zoom (per game for the toggle, session-global
    // for smoothing / snap-to-presets).
    val atlasFollowDefault: Boolean = false,
    val onAtlasFollowDefaultChange: (Boolean) -> Unit = {},
    val atlasSnapZoomToPresets: Boolean = true,
    val onAtlasSnapZoomChange: (Boolean) -> Unit = {},
    val atlasFollowSmoothing: FollowSmoothing = FollowSmoothing.Normal,
    val onAtlasFollowSmoothingChange: (FollowSmoothing) -> Unit = {},
    val atlasOrientation: jr.brian.thorhud.ui.map.custom.MapOrientation =
        jr.brian.thorhud.ui.map.custom.MapOrientation.NorthUp,
    val onAtlasOrientationChange: (jr.brian.thorhud.ui.map.custom.MapOrientation) -> Unit = {},
)

/**
 * Full-screen settings panel with the companion's session-wide
 * toggles, arranged as a two-column rail. Left rail is a collapsible
 * list of game groups (one per [panels] entry) plus a trailing "App"
 * group for game-agnostic rows; right pane shows the selected
 * section. Mirrors the layout proven in `AmmuNationScreen` so both
 * columns scroll independently and section switches do not touch a
 * pager.
 *
 * Built on [FullScreenSheet] because both columns scroll; a draggable
 * `ModalBottomSheet` would fight those scrolls. Dismissal is via the
 * close icon in the header or back.
 *
 * [panels] carries one entry per game to display. Setup screen
 * (disconnected) passes both IV and V so the user can configure
 * either; in-game only passes the active game. [initialPage] decides
 * which game's group auto-expands on first open. [visible] drives the
 * slide-in / slide-out animation — compose this unconditionally so
 * the exit animation plays before unmount.
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
    // Game-agnostic App section (sticky tab arrows). Collected once by
    // the caller and threaded in; the App chip in the global rail
    // group renders it.
    tabArrowsEnabled: Boolean = false,
    onTabArrowsChange: (Boolean) -> Unit = {},
    tabArrowIconStyle: TabArrowIconStyle = TabArrowIconStyle.Arrows,
    onTabArrowIconStyleChange: (TabArrowIconStyle) -> Unit = {},
    // Atlas feature flag. When false the atlas chip disappears from
    // every game group, matching the pager that already drops the
    // Atlas tab elsewhere in the app.
    atlasEnabled: Boolean = true,
) {
    require(panels.isNotEmpty()) { "SettingsSheet needs at least one panel" }
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

    val safeInitial = initialPage.coerceIn(0, panels.lastIndex)
    val initialGroup = groupKeyOfIndex(safeInitial)

    // Default route = the initial game's Cheats chip. Saveable so a
    // rotation keeps the user on the pane they were reading.
    var selectedRoute by rememberSaveable {
        mutableStateOf("$initialGroup.$SEC_CHEATS")
    }
    // Rail group expansion. Active game expands by default; the other
    // game and the global group stay collapsed so the common path is
    // one tap. SetSaver lets the user's expand state survive rotation.
    val expandedGroups = rememberSaveable(saver = SetSaver) {
        mutableStateOf(setOf(initialGroup))
    }
    val toggleGroup: (String) -> Unit = { key ->
        expandedGroups.value =
            if (key in expandedGroups.value) expandedGroups.value - key
            else expandedGroups.value + key
    }

    // Shell accent follows the owning game of the selected section;
    // global routes borrow the first panel's palette so the header
    // + divider still have a sensible accent.
    val activeGroupKey = selectedRoute.substringBefore('.')
    val activePanel = panelForGroup(panels, activeGroupKey) ?: panels[safeInitial]

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_title),
        accent = activePanel.palette.accent,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsRail(
                    panels = panels,
                    atlasEnabled = atlasEnabled,
                    globalAccent = panels.first().palette.accent,
                    selectedRoute = selectedRoute,
                    expandedGroups = expandedGroups.value,
                    onSelect = { selectedRoute = it },
                    onToggleGroup = toggleGroup,
                    modifier = Modifier
                        .width(RAIL_WIDTH)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 8.dp),
                )
                Spacer(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(activePanel.palette.accent.copy(alpha = 0.3f)),
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = 10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Pane(
                        route = selectedRoute,
                        panels = panels,
                        atlasEnabled = atlasEnabled,
                        tabArrowsEnabled = tabArrowsEnabled,
                        onTabArrowsChange = onTabArrowsChange,
                        tabArrowIconStyle = tabArrowIconStyle,
                        onTabArrowIconStyleChange = onTabArrowIconStyleChange,
                        onReconnectPlugin = onReconnectPlugin,
                        isReconnecting = isReconnecting,
                        activePalette = activePanel.palette,
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
 * Left column of the sheet. Each [panels] entry becomes a collapsible
 * group of section chips, followed by a global "App" group that
 * houses the game-agnostic rows (sticky arrows, plugin reconnect).
 */
@Composable
private fun SettingsRail(
    panels: List<SettingsGamePanel>,
    atlasEnabled: Boolean,
    globalAccent: Color,
    selectedRoute: String,
    expandedGroups: Set<String>,
    onSelect: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for ((index, panel) in panels.withIndex()) {
            val key = groupKeyOfIndex(index)
            val expanded = key in expandedGroups
            RailGroupHeader(
                title = panel.label,
                accent = panel.palette.accent,
                expanded = expanded,
                onClick = { onToggleGroup(key) },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    for ((routeKey, labelRes) in railSectionsFor(panel, atlasEnabled)) {
                        val route = "$key.$routeKey"
                        RailChip(
                            label = stringResource(labelRes),
                            selected = selectedRoute == route,
                            accent = panel.palette.accent,
                            onClick = { onSelect(route) },
                        )
                    }
                }
            }
        }
        val globalExpanded = GROUP_GLOBAL in expandedGroups
        RailGroupHeader(
            title = stringResource(R.string.settings_rail_group_app),
            accent = globalAccent,
            expanded = globalExpanded,
            onClick = { onToggleGroup(GROUP_GLOBAL) },
        )
        AnimatedVisibility(
            visible = globalExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                val appRoute = "$GROUP_GLOBAL.$SEC_APP"
                val pluginRoute = "$GROUP_GLOBAL.$SEC_PLUGIN"
                RailChip(
                    label = stringResource(R.string.settings_section_app),
                    selected = selectedRoute == appRoute,
                    accent = globalAccent,
                    onClick = { onSelect(appRoute) },
                )
                RailChip(
                    label = stringResource(R.string.settings_section_plugin),
                    selected = selectedRoute == pluginRoute,
                    accent = globalAccent,
                    onClick = { onSelect(pluginRoute) },
                )
            }
        }
    }
}

/** Ordered chip list for one game panel. Atlas and Ammu drop out
 *  when their flags say so, so the rail only shows what the pane
 *  actually has rows for. */
private fun railSectionsFor(
    panel: SettingsGamePanel,
    atlasEnabled: Boolean,
): List<Pair<String, Int>> {
    val list = mutableListOf<Pair<String, Int>>()
    list += SEC_CHEATS to R.string.settings_section_cheats
    list += SEC_MINIMAP to R.string.settings_section_minimap
    if (atlasEnabled) list += SEC_ATLAS to R.string.settings_section_atlas
    if (panel.showAmmuSection) list += SEC_AMMU to R.string.settings_section_ammu
    return list
}

@Composable
private fun RailGroupHeader(
    title: String,
    accent: Color,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                          else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RailChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val fg = if (selected) Color.Black else Color(0xFFEDEDED)
    val bg = if (selected) accent else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(
                1.dp,
                accent.copy(alpha = if (selected) 1f else 0.3f),
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** Right pane. Picks the content composable for the current route; a
 *  route that no longer resolves (e.g. restored saveable state against
 *  a now-shorter panel list) falls through to a placeholder. */
@Composable
private fun Pane(
    route: String,
    panels: List<SettingsGamePanel>,
    atlasEnabled: Boolean,
    tabArrowsEnabled: Boolean,
    onTabArrowsChange: (Boolean) -> Unit,
    tabArrowIconStyle: TabArrowIconStyle,
    onTabArrowIconStyleChange: (TabArrowIconStyle) -> Unit,
    onReconnectPlugin: () -> Unit,
    isReconnecting: Boolean,
    activePalette: HudPalette,
) {
    val groupKey = route.substringBefore('.')
    val sectionKey = route.substringAfter('.', missingDelimiterValue = "")
    val panel = panelForGroup(panels, groupKey)

    when {
        panel != null && sectionKey == SEC_CHEATS ->
            CheatsContent(panel)
        panel != null && sectionKey == SEC_MINIMAP ->
            MinimapContent(panel)
        panel != null && sectionKey == SEC_ATLAS && atlasEnabled ->
            AtlasContent(panel)
        panel != null && sectionKey == SEC_AMMU && panel.showAmmuSection ->
            AmmuContent(panel)
        groupKey == GROUP_GLOBAL && sectionKey == SEC_APP ->
            AppContent(
                palette = activePalette,
                tabArrowsEnabled = tabArrowsEnabled,
                onTabArrowsChange = onTabArrowsChange,
                tabArrowIconStyle = tabArrowIconStyle,
                onTabArrowIconStyleChange = onTabArrowIconStyleChange,
            )
        groupKey == GROUP_GLOBAL && sectionKey == SEC_PLUGIN ->
            PluginContent(
                palette = activePalette,
                onReconnect = onReconnectPlugin,
                isReconnecting = isReconnecting,
            )
        else -> Text(
            stringResource(R.string.settings_rail_pick),
            color = Color(0xFF8EA096),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun CheatsContent(panel: SettingsGamePanel) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
}

@Composable
private fun MinimapContent(panel: SettingsGamePanel) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
    }
}

@Composable
private fun AtlasContent(panel: SettingsGamePanel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AtlasFolderRow(
            display = panel.atlasFolderDisplay,
            onPick = panel.onPickAtlasFolder,
            onClear = panel.onClearAtlasFolder,
            palette = panel.palette,
        )
        Spacer(Modifier.height(14.dp))
        SettingRow(
            title = stringResource(R.string.atlas_setting_follow),
            body = stringResource(R.string.atlas_setting_follow_hint),
            checked = panel.atlasFollowDefault,
            onCheckedChange = panel.onAtlasFollowDefaultChange,
            palette = panel.palette,
        )
        Spacer(Modifier.height(14.dp))
        SettingRow(
            title = stringResource(R.string.atlas_setting_snap_zoom),
            body = stringResource(R.string.atlas_setting_snap_zoom_hint),
            checked = panel.atlasSnapZoomToPresets,
            onCheckedChange = panel.onAtlasSnapZoomChange,
            palette = panel.palette,
        )
        Spacer(Modifier.height(14.dp))
        SegmentedSettingRow(
            title = stringResource(R.string.atlas_setting_smoothing),
            options = FollowSmoothing.values().toList(),
            selected = panel.atlasFollowSmoothing,
            labelFor = { s ->
                val id = when (s) {
                    FollowSmoothing.Tight  -> R.string.atlas_setting_smoothing_tight
                    FollowSmoothing.Normal -> R.string.atlas_setting_smoothing_normal
                    FollowSmoothing.Loose  -> R.string.atlas_setting_smoothing_loose
                }
                stringResource(id)
            },
            onSelect = panel.onAtlasFollowSmoothingChange,
            palette = panel.palette,
        )
        Spacer(Modifier.height(14.dp))
        SegmentedSettingRow(
            title = stringResource(R.string.atlas_setting_orientation),
            options = listOf(
                jr.brian.thorhud.ui.map.custom.MapOrientation.NorthUp,
                jr.brian.thorhud.ui.map.custom.MapOrientation.HeadingUp,
            ),
            selected = panel.atlasOrientation,
            labelFor = { o ->
                val id = when (o) {
                    jr.brian.thorhud.ui.map.custom.MapOrientation.NorthUp ->
                        R.string.atlas_setting_orientation_north_up
                    jr.brian.thorhud.ui.map.custom.MapOrientation.HeadingUp ->
                        R.string.atlas_setting_orientation_heading_up
                }
                stringResource(id)
            },
            onSelect = panel.onAtlasOrientationChange,
            palette = panel.palette,
        )
    }
}

@Composable
private fun AmmuContent(panel: SettingsGamePanel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            title = stringResource(R.string.settings_ammu_auto_select_title),
            body = stringResource(R.string.settings_ammu_auto_select_body),
            checked = panel.autoSelectEquipped,
            onCheckedChange = panel.onAutoSelectEquippedChange,
            palette = panel.palette,
        )
    }
}

@Composable
private fun AppContent(
    palette: HudPalette,
    tabArrowsEnabled: Boolean,
    onTabArrowsChange: (Boolean) -> Unit,
    tabArrowIconStyle: TabArrowIconStyle,
    onTabArrowIconStyleChange: (TabArrowIconStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            title = stringResource(R.string.settings_tab_arrows_title),
            body = stringResource(R.string.settings_tab_arrows_body),
            checked = tabArrowsEnabled,
            onCheckedChange = onTabArrowsChange,
            palette = palette,
        )
        Spacer(Modifier.height(14.dp))
        SegmentedSettingRow(
            title = stringResource(R.string.settings_tab_arrows_style_title),
            options = TabArrowIconStyle.entries.toList(),
            selected = tabArrowIconStyle,
            labelFor = { style ->
                val id = when (style) {
                    TabArrowIconStyle.Arrows ->
                        R.string.settings_tab_arrows_style_arrows
                    TabArrowIconStyle.Stars ->
                        R.string.settings_tab_arrows_style_stars
                    TabArrowIconStyle.Transparent ->
                        R.string.settings_tab_arrows_style_transparent
                }
                stringResource(id)
            },
            onSelect = onTabArrowIconStyleChange,
            palette = palette,
        )
    }
}

@Composable
private fun PluginContent(
    palette: HudPalette,
    onReconnect: () -> Unit,
    isReconnecting: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ActionRow(
            title = stringResource(R.string.settings_reconnect_title),
            body = stringResource(R.string.settings_reconnect_body),
            buttonLabel = stringResource(R.string.settings_reconnect_button),
            onClick = onReconnect,
            busy = isReconnecting,
            palette = palette,
        )
    }
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
private fun <T> SegmentedSettingRow(
    title: String,
    options: List<T>,
    selected: T,
    labelFor: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    palette: HudPalette,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (opt in options) {
                val chosen = opt == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (chosen) palette.accent else Color(0xFF1F2A24))
                        .clickable(
                            role = Role.Button,
                            onClick = { if (!chosen) onSelect(opt) },
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        labelFor(opt),
                        color = if (chosen) Color.Black else Color(0xFFB6CCBC),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
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

private fun groupKeyOfIndex(index: Int): String = "g$index"

private fun panelForGroup(
    panels: List<SettingsGamePanel>,
    key: String,
): SettingsGamePanel? {
    if (key == GROUP_GLOBAL) return null
    val idx = key.removePrefix("g").toIntOrNull() ?: return null
    return panels.getOrNull(idx)
}

private const val GROUP_GLOBAL = "global"
private const val SEC_CHEATS = "cheats"
private const val SEC_MINIMAP = "minimap"
private const val SEC_ATLAS = "atlas"
private const val SEC_AMMU = "ammu"
private const val SEC_APP = "app"
private const val SEC_PLUGIN = "plugin"
private val RAIL_WIDTH = 140.dp

/** rememberSaveable's default saver cannot hold a Set; the comma-join
 *  trick (same pattern used in AmmuNationScreen / LSCustomsScreen) lets
 *  rail-group expansion survive rotation. */
private val SetSaver = androidx.compose.runtime.saveable.Saver<
    androidx.compose.runtime.MutableState<Set<String>>, String>(
    save = { it.value.joinToString(",") },
    restore = { stored ->
        mutableStateOf(
            if (stored.isEmpty()) emptySet()
            else stored.split(",").toSet()
        )
    },
)
