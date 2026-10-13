package jr.brian.thorhud.ui.map

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import jr.brian.thorhud.BuildConfig
import jr.brian.thorhud.R

/**
 * The map's blips, nearest first, grouped by name. Each group header
 * is a tap-to-expand row showing the title-cased label, the nearest
 * blip's distance, and the number of blips in the group. Single-item
 * groups route in one tap; multi-item groups expand to show each
 * instance with its own route button. The routed one is highlighted
 * from the state frame, not from the tap, so a row only lights up when
 * the game confirmed the waypoint.
 */
@Composable
internal fun BlipListPanel(
    groups: List<BlipGroup>,
    accent: Color,
    onRoute: (Int) -> Unit,
    onClear: () -> Unit,
    onLogCensus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Hide the distance column entirely when the game's blip-coord
    // natives can't be trusted (currently GTA IV; see MainScreen's
    // BlipsOverlay for why). Groups are still sorted nearest-first.
    showDistance: Boolean = true,
) {
    val dim = Color(0xFF6A7A6E)
    // One section at a time: expanding a group hides every other
    // group's header with an animation; the panel becomes just that
    // one section and its children. null means "nothing expanded,
    // show every group's header". rememberSaveable so a rotation or
    // census refresh does not snap the list closed.
    var expandedName by rememberSaveable { mutableStateOf<String?>(null) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // Save the scroll offset when the first expand of a session
    // happens, restore it when the user collapses back to the full
    // list — otherwise the accordion collapse coerces the Column to
    // its new (shorter) max and the user loses their place.
    var savedScroll by rememberSaveable { mutableIntStateOf(0) }
    var prevExpanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expandedName) {
        val cur = expandedName
        val prev = prevExpanded
        when {
            cur != null && prev == null -> savedScroll = scrollState.value
            cur == null && prev != null -> scrollState.animateScrollTo(savedScroll)
        }
        prevExpanded = cur
    }
    val totalBlips = groups.sumOf { it.count }
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
                    if (totalBlips == 0) stringResource(R.string.map_blips_none)
                    else stringResource(R.string.map_blips_count, totalBlips),
                    color = dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            SmallButton(stringResource(R.string.map_blips_clear), accent, onClear)
            Spacer(Modifier.width(8.dp))
            SmallButton(stringResource(R.string.map_blips_info), accent, { showInfo = true })
            Spacer(Modifier.width(8.dp))
            // Writing the full census table to the plugin log is a dev
            // affordance for seeding sprite labels — not useful to the
            // player, so hide outside debug builds.
            if (BuildConfig.DEBUG) {
                SmallButton(stringResource(R.string.map_blips_log), accent, onLogCensus)
                Spacer(Modifier.width(8.dp))
            }
            SmallButton(stringResource(R.string.map_blips_close), accent, onClose)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showInfo) BlipInfoDialog(accent = accent, onDismiss = { showInfo = false })
            for (group in groups) {
                val isOpen = expandedName == group.name
                // Hide every other group's header while one is open.
                // Single-item groups never expand (nothing to show), so
                // they still visibly collapse out of the way.
                val visible = expandedName == null || isOpen
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupHeader(
                            group = group,
                            accent = accent,
                            expanded = isOpen,
                            onToggle = {
                                expandedName = if (isOpen) null else group.name
                            },
                            onRoute = onRoute,
                            onClear = onClear,
                            showDistance = showDistance,
                        )
                        AnimatedVisibility(
                            visible = isOpen && group.count > 1,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                group.rows.forEachIndexed { i, row ->
                                    ChildRow(
                                        row = row, accent = accent,
                                        onRoute = onRoute, onClear = onClear,
                                        label = if (showDistance) row.distance
                                                else "#${i + 1}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: BlipGroup,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRoute: (Int) -> Unit,
    onClear: () -> Unit,
    showDistance: Boolean,
) {
    val text = Color(0xFFB6CCBC)
    val dim = Color(0xFF6A7A6E)
    val single = group.count == 1
    val singleRow = group.rows.first()
    // Tap target: single-item groups toggle route — set if not routed,
    // clear if already routed; multi-item groups toggle expand.
    val onRowTap: () -> Unit = when {
        !single                 -> onToggle
        singleRow.routed        -> onClear
        else                    -> ({ onRoute(singleRow.blip.id) })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (group.routed) Color(0xFF26352C) else Color(0xFF161D19))
            .clickable(role = Role.Button, onClick = onRowTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountBox(count = group.count, accent = accent)
        Spacer(Modifier.width(12.dp))
        val title = if (showDistance) "${group.name.titleCase()} · ${group.nearest}"
                    else group.name.titleCase()
        Text(
            title,
            color = if (group.missionStart) accent else text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Trailing: route button for single groups, expand chevron for
        // multi-item groups.
        if (single) {
            SmallButton(
                label = stringResource(
                    if (singleRow.routed) R.string.map_blips_routed
                    else R.string.map_blips_route,
                ),
                accent = accent,
                highlighted = singleRow.routed,
                onClick = if (singleRow.routed) onClear
                          else ({ onRoute(singleRow.blip.id) }),
            )
        } else {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                              else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = dim,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The "about the blip list" dialog behind the header's info button.
 * Blip labels in the companion are provisional (seeded from the FiveM
 * sprite reference for V and the GTAMods wiki for IV, with the
 * TBoGT/TLAD contact ids being guessed from decompiled scripts), and
 * routing to an unknown row is the easiest way to confirm what it
 * actually is. The dialog explains that and opens the repo's issues
 * page so a player can send back "sprite N was Mom's House".
 */
@Composable
private fun BlipInfoDialog(accent: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.map_blips_info_title),
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                stringResource(R.string.map_blips_info_body),
                color = Color(0xFFB6CCBC),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW,
                           Uri.parse("https://github.com/BrianJr03/GrandTheftCompanion/issues/new"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                onDismiss()
            }) {
                Text(
                    stringResource(R.string.map_blips_info_open_issue),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.map_blips_close),
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        containerColor = Color(0xFF101713),
    )
}

/** Accent-filled pill with the group's blip count in white. The fill
 *  is slightly transparent so it sits a touch darker than the raw
 *  accent and does not shout over the row's own tint. */
@Composable
private fun CountBox(count: Int, accent: Color) {
    Box(
        modifier = Modifier
            .heightIn(min = 28.dp)
            .widthIn(min = 32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChildRow(
    row: BlipRow,
    accent: Color,
    onRoute: (Int) -> Unit,
    onClear: () -> Unit,
    label: String,
) {
    val text = Color(0xFFB6CCBC)
    val tap: () -> Unit = if (row.routed) onClear
                          else ({ onRoute(row.blip.id) })
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (row.routed) Color(0xFF26352C) else Color(0xFF101713))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        SmallButton(
            label = stringResource(
                if (row.routed) R.string.map_blips_routed
                else R.string.map_blips_route,
            ),
            accent = accent,
            highlighted = row.routed,
            onClick = tap,
        )
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
