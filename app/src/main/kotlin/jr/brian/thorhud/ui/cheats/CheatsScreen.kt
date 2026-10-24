package jr.brian.thorhud.ui.cheats

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.FullScreenSheet
import jr.brian.thorhud.ui.components.HeaderIconButton
import kotlinx.coroutines.launch

/**
 * The cheats tab. Grouped into collapsible sections (health, wanted,
 * weapons, vehicle, world, toggles) so related cheats stay visually
 * clustered on the Thor's short 540 x 468 dp bottom panel. Toggle
 * button lights come from [cheatsMask] alone — never from the tap —
 * so a toggle the plugin refused never looks on. The last refusal
 * shows as a single line at the bottom; cleared on the next tap.
 *
 * Long-press on a non-toggle (one-shot) button toggles its
 * auto-apply flag: a lit one-shot fires itself on every reconnect
 * once the game is in a safe state (same reconciler as toggles,
 * same master switch in Settings). The user confirms via a toast.
 *
 * First visit shows a two-slide onboarding bottom sheet with the
 * tap / long-press rules. Dismissal is persisted; it never shows
 * again after that.
 *
 * When the connected plugin build does not support the cheat
 * command the screen is just a one-line notice.
 */
@Composable
internal fun CheatsScreen(
    isGtaV: Boolean,
    cheatsSupported: Boolean?,
    cheatsMask: Int?,
    vehicle: GameState.Vehicle?,
    accent: Color,
    lastResult: CheatResult?,
    collapsedSections: Set<String>,
    autoApplyCheats: Set<Int>,
    savedSectionOrder: List<String>,
    showOnboarding: Boolean,
    onToggleSection: (String, Boolean) -> Unit,
    onRunCheat: (Int) -> Unit,
    onLongPressOneShot: (Int) -> Boolean,
    onPaintRgb: (Long) -> Unit,
    // Called with the full list of CheatCategory.name values after
    // the user finishes a drag; the ViewModel persists it as-is.
    onReorderSections: (List<String>) -> Unit,
    onOnboardingDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRadio: (() -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
    onOpenHud: (() -> Unit)? = null,
) {
    val dim = Color(0xFF6A7A6E)
    val context = LocalContext.current
    // Colour-wheel sheet state. Shown by the Paint section's 4th
    // button (V only). onPick routes through onPaintRgb — same wire
    // path as a swatch tap, so no new plugin code is involved.
    var showColorWheel by rememberSaveable { mutableStateOf(false) }
    var lastWheelPicked by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.cheats_title),
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            onOpenHud?.let { open ->
                HeaderIconButton(
                    icon = Icons.Filled.Home,
                    contentDescription = stringResource(R.string.nav_hud_cd),
                    tint = accent,
                    onClick = open,
                )
            }
            onOpenRadio?.let { open ->
                HeaderIconButton(
                    icon = Icons.Filled.Radio,
                    contentDescription = stringResource(R.string.label_radio),
                    tint = accent,
                    onClick = open,
                )
            }
            onOpenMap?.let { open ->
                HeaderIconButton(
                    icon = Icons.Filled.DirectionsCar,
                    contentDescription = stringResource(R.string.cheats_map_cd),
                    tint = accent,
                    onClick = open,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (cheatsSupported != true) {
            Text(
                stringResource(R.string.cheats_unsupported),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            return@Column
        }

        val grouped = visibleCheats(isGtaV)
        val pendingId = lastResult?.takeIf { it.error == null }?.cheatId

        // Short-tap: fire. Long-press on a one-shot: toggle its
        // auto-apply membership, then toast the new state. Toasts
        // use the companion's own labels so the user never has to
        // match a toast to a button by position.
        val onLongPress: (CheatDef) -> Unit = { def ->
            if (!def.isToggle) {
                val nowOn = onLongPressOneShot(def.id)
                val label = context.getString(def.labelRes)
                val msg = context.getString(
                    if (nowOn) R.string.cheats_auto_apply_on
                    else R.string.cheats_auto_apply_off,
                    label,
                )
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Alphabetical-by-localized-label default. Context is only
        // reachable from Compose, so the sort happens here, not in
        // the pref layer. New categories added in a later build drop
        // in at their alphabetical slot the first time; after a user
        // reorders, resolveSectionOrder appends them to the end.
        val available = grouped.keys
        val defaultOrder = available.sortedBy { context.getString(it.labelRes).lowercase() }
        val effectiveOrder = resolveSectionOrder(savedSectionOrder, available, defaultOrder)

        // Drag/drop state. Finger Y is tracked in Column-root space;
        // onGloballyPositioned on each section records its top/bottom
        // bounds in the same space so the hover check is a plain
        // range contains(). Dropping outside any header or on the
        // originating index is a no-op.
        val sectionBounds = remember { mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>() }
        var dragFromIdx by remember { mutableStateOf<Int?>(null) }
        var dragFingerY by remember { mutableFloatStateOf(0f) }
        val hoverIdx: Int? = run {
            if (dragFromIdx == null) null
            else sectionBounds.entries
                .firstOrNull { (_, range) -> dragFingerY in range }
                ?.key
        }

        effectiveOrder.forEachIndexed { idx, category ->
            val items = grouped[category].orEmpty()
            val expanded = category.name !in collapsedSections
            Box(
                modifier = Modifier.onGloballyPositioned { c ->
                    // c.boundsInParent() returns a Rect in the parent
                    // Column's coordinate space (y grows downward); its
                    // top/bottom are Floats, which match dragFingerY.
                    val rect = c.boundsInParent()
                    sectionBounds[idx] = rect.top..rect.bottom
                },
            ) {
                CategorySection(
                    category = category,
                    items = items,
                    accent = accent,
                    pendingId = pendingId,
                    cheatsMask = cheatsMask,
                    autoApplyCheats = autoApplyCheats,
                    expanded = expanded,
                    onToggleExpanded = { onToggleSection(category.name, expanded) },
                    onTap = onRunCheat,
                    onLongPress = onLongPress,
                    isDropTarget = hoverIdx == idx
                        && dragFromIdx != null
                        && dragFromIdx != idx,
                    isDragging = dragFromIdx == idx,
                    dragHandleModifier = Modifier.pointerInput(idx, effectiveOrder) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragFromIdx = idx
                                // Finger starts somewhere inside this
                                // section's row; parking the Y at the row's
                                // vertical center is good enough — the
                                // first onDrag delta puts it on the real
                                // position within a frame.
                                val range = sectionBounds[idx]
                                dragFingerY = if (range != null)
                                    (range.start + range.endInclusive) * 0.5f
                                else 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragFingerY += dragAmount.y
                            },
                            onDragEnd = {
                                val from = dragFromIdx
                                val to = hoverIdx
                                if (from != null && to != null && from != to) {
                                    val reordered = reorderList(effectiveOrder, from, to)
                                    onReorderSections(reordered.map { it.name })
                                }
                                dragFromIdx = null
                                dragFingerY = 0f
                            },
                            onDragCancel = {
                                dragFromIdx = null
                                dragFingerY = 0f
                            },
                        )
                    },
                    body = if (category == CheatCategory.Paint) {
                        { PaintSection(
                            items = items,
                            isGtaV = isGtaV,
                            vehicle = vehicle,
                            accent = accent,
                            pendingId = pendingId,
                            cheatsMask = cheatsMask,
                            autoApplyCheats = autoApplyCheats,
                            onTap = onRunCheat,
                            onLongPress = onLongPress,
                            onPaintRgb = onPaintRgb,
                            onOpenWheel = if (isGtaV)
                                { -> showColorWheel = true }
                            else null,
                        ) }
                    } else null,
                )
            }
        }

        // Last refusal, verbatim from the plugin. Blank line when the
        // last tap was accepted (or nothing has been tapped).
        val err = lastResult?.error
        if (!err.isNullOrBlank()) {
            Text(
                stringResource(R.string.cheats_error, err),
                color = Color(0xFFE07070),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    CheatsOnboardingSheet(
        visible = showOnboarding,
        accent = accent,
        onDismiss = onOnboardingDismissed,
    )

    // The colour wheel. Always composed so the slide-out animation
    // plays on dismiss; `visible` gates whether it is on screen. The
    // picked RGB rides onPaintRgb — exactly the same wire path a
    // swatch tap uses, so no new plugin code is involved.
    ColorWheelSheet(
        visible = showColorWheel,
        accent = accent,
        initial = lastWheelPicked,
        onDismiss = { showColorWheel = false },
        onPick = { packed ->
            lastWheelPicked = packed
            onPaintRgb(packed)
        },
    )
}

@Composable
private fun CategorySection(
    category: CheatCategory,
    items: List<CheatDef>,
    accent: Color,
    pendingId: Int?,
    cheatsMask: Int?,
    autoApplyCheats: Set<Int>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTap: (Int) -> Unit,
    onLongPress: (CheatDef) -> Unit,
    // Drag/drop decoration. The caller attaches a pointerInput to the
    // handle icon via [dragHandleModifier]; this composable just paints
    // the handle (leading) and lights the row border on hover.
    dragHandleModifier: Modifier = Modifier,
    isDropTarget: Boolean = false,
    isDragging: Boolean = false,
    // Alternate body for sections that are not a plain two-wide grid
    // (today: Paint, which also shows the current pair and a V-only
    // swatch row). Default null = render the shared CheatGrid.
    body: (@Composable () -> Unit)? = null,
) {
    val dim = Color(0xFF6A7A6E)
    // Expand/collapse state is hoisted to the ViewModel and persisted
    // in HudPrefs so collapsing a section survives app relaunch.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Hover highlight: a thin accent border + a touch of fill
            // on the drop target so the user can see where the section
            // will land before letting go.
            .let { if (isDropTarget) it.border(1.dp, accent, RoundedCornerShape(4.dp)) else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle. Six-dot icon; long-press on this icon starts
            // the section reorder. The handle has its OWN pointerInput
            // so the chevron / label can still short-tap to collapse.
            Icon(
                imageVector = Icons.Filled.DragIndicator,
                contentDescription = stringResource(R.string.cheats_section_reorder_cd),
                tint = if (isDragging) accent else dim,
                modifier = Modifier
                    .size(18.dp)
                    .then(dragHandleModifier),
            )
            Spacer(Modifier.size(4.dp))
            // Chevron + label + count: short-tap collapses. Click lives
            // on this inner Row so the drag handle to its left is NOT a
            // tap target for collapse.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                                  else Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(category.labelRes),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "(${items.size})",
                    color = dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                if (body != null) {
                    body()
                } else {
                    CheatGrid(
                        items = items,
                        accent = accent,
                        pendingId = pendingId,
                        cheatsMask = cheatsMask,
                        autoApplyCheats = autoApplyCheats,
                        onTap = onTap,
                        onLongPress = onLongPress,
                    )
                }
            }
        }
    }
}

/**
 * Paint section body. Renders the current colour pair as two outlined
 * boxes (reading from [vehicle] — never from the tap), the three
 * catalog buttons through the shared grid, and (V only) a swatch row
 * that calls [onPaintRgb] with the packed RGB. When [vehicle] is
 * null, the whole thing greys out with the "get in a car" hint and
 * the taps are disabled so the plugin never sees a doomed command.
 */
@Composable
private fun PaintSection(
    items: List<CheatDef>,
    isGtaV: Boolean,
    vehicle: GameState.Vehicle?,
    accent: Color,
    pendingId: Int?,
    cheatsMask: Int?,
    autoApplyCheats: Set<Int>,
    onTap: (Int) -> Unit,
    onLongPress: (CheatDef) -> Unit,
    onPaintRgb: (Long) -> Unit,
    // V-only "open the colour wheel" callback. Null on IV so the
    // button never renders — the plugin would refuse paint_rgb there
    // with "not in this game" anyway.
    onOpenWheel: (() -> Unit)? = null,
) {
    val inCar = vehicle != null
    val fade = if (inCar) 1f else 0.4f
    val dim = Color(0xFF6A7A6E)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Pair readout. On V with a custom override active, show the
        // word "custom" in place of both indices; otherwise show the
        // palette indices from the state frame. On IV the vehicle
        // block never carries `custom`, so we fall through to indices.
        val custom = vehicle?.custom == true
        val c1 = vehicle?.colour1
        val c2 = vehicle?.colour2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PairBox(
                label = stringResource(R.string.cheats_paint_primary),
                value = when {
                    !inCar      -> "—"
                    custom      -> stringResource(R.string.cheats_paint_custom)
                    c1 != null  -> c1.toString()
                    else        -> "—"
                },
                accent = accent.copy(alpha = fade),
                dim = dim.copy(alpha = fade),
                modifier = Modifier.weight(1f),
            )
            PairBox(
                label = stringResource(R.string.cheats_paint_secondary),
                value = when {
                    !inCar      -> "—"
                    custom      -> stringResource(R.string.cheats_paint_custom)
                    c2 != null  -> c2.toString()
                    else        -> "—"
                },
                accent = accent.copy(alpha = fade),
                dim = dim.copy(alpha = fade),
                modifier = Modifier.weight(1f),
            )
        }

        if (!inCar) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.cheats_paint_get_in_car),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Buttons. We route a disabled tap (on foot) through a
        // no-op so the plugin never sees a doomed command and the
        // user never sees the "not in a vehicle" error line.
        CheatGrid(
            items = items,
            accent = accent.copy(alpha = fade),
            pendingId = pendingId,
            cheatsMask = cheatsMask,
            autoApplyCheats = autoApplyCheats,
            onTap = if (inCar) onTap else { _ -> },
            onLongPress = if (inCar) onLongPress else { _ -> },
        )

        // "Custom colour" opens the colour wheel. V-only (IV refuses
        // paint_rgb at the plugin, so the button never renders on
        // IV). Lives directly under the catalog row so the three
        // standard buttons + this one feel like one cluster.
        //
        // Deliberately NOT gated on inCar: opening the picker is
        // harmless, and the player can prepare a colour before
        // getting in a car. The Apply inside the sheet calls
        // paintRgb, which the plugin refuses with "not in a vehicle"
        // on foot — that's where the error wants to live.
        if (onOpenWheel != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black)
                        .border(1.dp, accent, RoundedCornerShape(50))
                        .clickable { onOpenWheel() }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cheats_paint_custom_colour),
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                // Pad the second slot so this button keeps the
                // half-width look of the other paint buttons.
                Spacer(Modifier.weight(1f))
            }
        }

        if (isGtaV) {
            Spacer(Modifier.height(8.dp))
            SwatchRow(
                accent = accent.copy(alpha = fade),
                enabled = inCar,
                onPick = onPaintRgb,
            )
        }
    }
}

@Composable
private fun PairBox(
    label: String,
    value: String,
    accent: Color,
    dim: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Text(
            label,
            color = dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** V-only swatch row. Eight tappable filled boxes; each one sends a
 *  paint_rgb with its packed colour. Greyed out when the player is on
 *  foot so a tap cannot ride into the plugin. */
@Composable
private fun SwatchRow(
    accent: Color,
    enabled: Boolean,
    onPick: (Long) -> Unit,
) {
    val label = stringResource(R.string.cheats_paint_custom)
    Column {
        Text(
            label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (sw in PAINT_SWATCHES) {
                val (r, g, b) = unpackRgb(sw.packed)
                val fill = Color(r, g, b).copy(alpha = if (enabled) 1f else 0.3f)
                val cd = stringResource(sw.labelRes)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(fill)
                        .border(1.dp, accent, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = cd }
                        .clickable(enabled = enabled) { onPick(sw.packed) },
                )
            }
        }
    }
}


@Composable
private fun CheatGrid(
    items: List<CheatDef>,
    accent: Color,
    pendingId: Int?,
    cheatsMask: Int?,
    autoApplyCheats: Set<Int>,
    onTap: (Int) -> Unit,
    onLongPress: (CheatDef) -> Unit,
) {
    // Two columns of buttons. Pairs the list up and lets any trailing
    // odd entry take a column of its own. The 540 x 468 dp Thor bottom
    // panel comfortably fits 2 x ~250 dp buttons at this font size.
    val rows = items.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { def ->
                    val on = if (def.isToggle) isOn(cheatsMask, def.id)
                             else autoApplyCheats.contains(def.id)
                    CheatButton(
                        def = def,
                        accent = accent,
                        on = on,
                        pending = pendingId == def.id,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        modifier = Modifier.weight(1f),
                    )
                }
                // When the row has only one entry, pad the second slot
                // so the surviving button keeps its half-width look.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheatButton(
    def: CheatDef,
    accent: Color,
    on: Boolean,
    pending: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (CheatDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Styled as a pill like Material's OutlinedButton / FilledTonalButton
    // but implemented as a Box so Modifier.combinedClickable's long-press
    // works on every tap — Material's Button composable swallows the
    // long press even with a custom gesture modifier chained on top.
    val label = stringResource(def.labelRes)
    val shape = RoundedCornerShape(50)
    val containerColor = if (on) accent else Color.Black
    val textColor = if (on) Color.Black else accent
    val borderColor = accent
    val alpha = if (pending) 0.5f else 1f
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(containerColor.copy(alpha = alpha))
            .border(1.dp, borderColor.copy(alpha = alpha), shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !pending,
                onClick = { onTap(def.id) },
                onLongClick = { onLongPress(def) },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
}

/** Two-slide first-visit onboarding. One slide introducing taps +
 *  lights, one slide introducing long-press auto-apply and pointing
 *  at the Settings override. Full-screen slide-up so swipe between
 *  pages never fights the sheet's drag. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheatsOnboardingSheet(
    visible: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()
    val dim = Color(0xFF6A7A6E)
    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.cheats_onboarding_title),
        accent = accent,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                val title = if (page == 0) R.string.cheats_onboarding_slide1_title
                            else R.string.cheats_onboarding_slide2_title
                val body = if (page == 0) R.string.cheats_onboarding_slide1_body
                           else R.string.cheats_onboarding_slide2_body
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(title),
                        color = Color(0xFFEDEDED),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(body),
                        color = Color(0xFFCFCFCF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // Two dot indicators + action button on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(2) { idx ->
                    val lit = pager.currentPage == idx
                    Box(
                        modifier = Modifier
                            .size(if (lit) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (lit) accent else dim),
                    )
                    if (idx == 0) Spacer(Modifier.size(6.dp))
                }
                Spacer(Modifier.weight(1f))
                val onPrimary: () -> Unit = {
                    if (pager.currentPage < 1) {
                        scope.launch { pager.animateScrollToPage(1) }
                    } else {
                        onDismiss()
                    }
                }
                TextButton(onClick = onPrimary) {
                    Text(
                        stringResource(
                            if (pager.currentPage < 1) R.string.cheats_onboarding_next
                            else R.string.cheats_onboarding_done,
                        ),
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
