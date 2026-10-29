package jr.brian.thorhud.ui.cheats

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import jr.brian.thorhud.ui.components.HeaderIconButton
import jr.brian.thorhud.ui.components.OnboardingSheet
import jr.brian.thorhud.ui.components.OnboardingSlide

/**
 * The cheats tab. Two-column rail layout: a left column of category
 * chips (health, wanted, weapons, vehicle, world, toggles, paint) and
 * a right pane that renders the selected category's buttons. Mirrors
 * `SettingsSheet` and `AmmuNationScreen` so swapping tabs feels
 * uniform. Toggle button lights come from [cheatsMask] alone — never
 * from the tap — so a toggle the plugin refused never looks on. The
 * last refusal shows as a single line at the bottom of the pane;
 * cleared on the next tap.
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
 *
 * [collapsedSections] and [onToggleSection] + [onReorderSections]
 * stay in the signature so the ViewModel pref layer keeps working
 * unchanged — the rail does not use them, but the parent (MainScreen)
 * passes them unconditionally.
 */
@Composable
internal fun CheatsScreen(
    isGtaV: Boolean,
    cheatsSupported: Boolean?,
    cheatsMask: Int?,
    vehicle: GameState.Vehicle?,
    accent: Color,
    lastResult: CheatResult?,
    @Suppress("UNUSED_PARAMETER") collapsedSections: Set<String>,
    autoApplyCheats: Set<Int>,
    savedSectionOrder: List<String>,
    showOnboarding: Boolean,
    @Suppress("UNUSED_PARAMETER") onToggleSection: (String, Boolean) -> Unit,
    onRunCheat: (Int) -> Unit,
    onLongPressOneShot: (Int) -> Boolean,
    onPaintRgb: (Long) -> Unit,
    // Paint-to-lights toggle: ViewModel reads GtcSettings so the flip
    // persists across tab changes + launches. V-only; callback null
    // on IV hides the toggle row.
    paintToLightsEnabled: Boolean = false,
    onPaintToLightsChange: ((Boolean) -> Unit)? = null,
    // Section reorder stays in the API for forward-compat; the rail
    // does not offer a reorder gesture today (the old drag UI was
    // dead code, so parity is preserved).
    @Suppress("UNUSED_PARAMETER") onReorderSections: (List<String>) -> Unit,
    onOnboardingDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRadio: (() -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
    onOpenHud: (() -> Unit)? = null,
    // Paused only (both games): a gear sits at the far right of the
    // header so settings is reachable from this tab without swiping
    // back to the paused HUD. Caller passes null when the game is
    // unpaused so the icon stays hidden.
    onOpenSettings: (() -> Unit)? = null,
) {
    val dim = Color(0xFF6A7A6E)
    val context = LocalContext.current
    // Colour-wheel sheet state. Shown by the Paint section's 4th
    // button (V only). onPick routes through onPaintRgb — same wire
    // path as a swatch tap, so no new plugin code is involved.
    var showColorWheel by rememberSaveable { mutableStateOf(false) }
    var lastWheelPicked by rememberSaveable { mutableStateOf<Long?>(null) }
    // Explicit Box so the sheets overlay the main Column reliably.
    // The pager page's layout scope allowed multiple siblings before,
    // but ordering + Z-index of a slide-up sheet (AnimatedVisibility
    // with fillMaxSize content) depends on the parent being Box-like.
    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
            onOpenSettings?.let { open ->
                HeaderIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings_icon_cd),
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
                fontSize = 16.sp,
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
        // reorders (via a future gesture), resolveSectionOrder
        // appends them to the end.
        val available = grouped.keys
        val defaultOrder = available.sortedBy { context.getString(it.labelRes).lowercase() }
        val effectiveOrder = resolveSectionOrder(savedSectionOrder, available, defaultOrder)

        // Selected rail chip. Saveable so rotation keeps the user on
        // the category they were reading. Falls back to the first
        // category in effective order on first open or when the
        // saved selection no longer exists (e.g. plugin dropped a
        // category between builds).
        val defaultSelection = effectiveOrder.firstOrNull()?.name
        var selectedName by rememberSaveable {
            mutableStateOf(defaultSelection)
        }
        val resolvedSelected = effectiveOrder.firstOrNull { it.name == selectedName }
            ?: effectiveOrder.firstOrNull()
        val selected = resolvedSelected

        Row(modifier = Modifier.fillMaxSize()) {
            CategoryRail(
                categories = effectiveOrder,
                selected = selected,
                accent = accent,
                onSelect = { selectedName = it.name },
                modifier = Modifier
                    .width(RAIL_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
            )
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(accent.copy(alpha = 0.3f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 6.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val items = selected?.let { grouped[it].orEmpty() }.orEmpty()
                if (selected == null) {
                    Text(
                        stringResource(R.string.cheats_pick_section),
                        color = dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                } else if (selected == CheatCategory.Paint) {
                    PaintSection(
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
                        onOpenWheel = if (isGtaV) {
                            { showColorWheel = true }
                        } else null,
                        paintToLightsEnabled = paintToLightsEnabled,
                        onPaintToLightsChange = if (isGtaV) onPaintToLightsChange else null,
                    )
                } else {
                    CheatGrid(
                        items = items,
                        accent = accent,
                        pendingId = pendingId,
                        cheatsMask = cheatsMask,
                        autoApplyCheats = autoApplyCheats,
                        onTap = onRunCheat,
                        onLongPress = onLongPress,
                    )
                }

                // Last refusal, verbatim from the plugin. Blank when
                // the last tap was accepted (or nothing has been
                // tapped). Sits inside the pane column so it only
                // takes space when there's a message to show.
                val err = lastResult?.error
                if (!err.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cheats_error, err),
                        color = Color(0xFFE07070),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }

    OnboardingSheet(
        visible = showOnboarding,
        title = stringResource(R.string.cheats_onboarding_title),
        accent = accent,
        slides = CHEATS_ONBOARDING_SLIDES,
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
    }   // Box
}

/**
 * Left-column rail of category chips. One chip per category in
 * [categories], in that order; the selected chip paints a filled
 * accent background. Labels alone — the per-chip count was cramping
 * the 100 dp rail, and the pane header already implies how much is
 * inside.
 */
@Composable
private fun CategoryRail(
    categories: List<CheatCategory>,
    selected: CheatCategory?,
    accent: Color,
    onSelect: (CheatCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for (category in categories) {
            RailChip(
                label = stringResource(category.labelRes),
                selected = selected == category,
                accent = accent,
                onClick = { onSelect(category) },
            )
        }
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
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
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
    // Paint-to-lights mirror toggle. V-only (nothing writes
    // lastPaintedRgb on IV). State is a Boolean + a change callback,
    // sourced from GtcSettings.paintToLightsEnabled.
    paintToLightsEnabled: Boolean = false,
    onPaintToLightsChange: ((Boolean) -> Unit)? = null,
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
                fontSize = 15.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        // 2-wide button grid for the catalog paint defs (respray /
        // next color / swap colors / fix vehicle). Taps on foot are
        // routed to no-op so the plugin never sees a doomed command.
        // On V the colour-wheel pill rides on its own trailing row so
        // the catalog + wheel still fit without re-chunking the grid;
        // the wheel is deliberately NOT gated on inCar — opening the
        // picker is harmless and the Apply inside calls paintRgb
        // which the plugin refuses with "not in a vehicle" on foot.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rows = items.chunked(2)
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowItems.forEach { def ->
                        val on = if (def.isToggle) isOn(cheatsMask, def.id)
                                 else autoApplyCheats.contains(def.id)
                        CheatButton(
                            def = def,
                            accent = accent.copy(alpha = fade),
                            on = on,
                            pending = pendingId == def.id,
                            onTap = if (inCar) onTap else { _ -> },
                            onLongPress = if (inCar) onLongPress else { _ -> },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (onOpenWheel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CustomColorButton(
                        accent = accent,
                        onClick = onOpenWheel,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        if (isGtaV) {
            Spacer(Modifier.height(8.dp))
            SwatchRow(
                accent = accent.copy(alpha = fade),
                enabled = inCar,
                onPick = onPaintRgb,
            )

            // Mirror the last paint_rgb pick to the Thor's thumbstick
            // lights. V-only because the mirror reads
            // GameStateRepository.lastPaintedRgb which only ever gets
            // set on a V paint_rgb command. Switch state is held in
            // GtcSettings so a flip survives tab changes + relaunch;
            // PoliceLightsController still wins when the player is
            // wanted.
            if (onPaintToLightsChange != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPaintToLightsChange(!paintToLightsEnabled) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.cheats_paint_to_lights),
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = paintToLightsEnabled,
                        onCheckedChange = onPaintToLightsChange,
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = accent,
                            uncheckedTrackColor = Color.Black,
                            uncheckedBorderColor = accent,
                        ),
                    )
                }
            }
        }
    }
}

/** Half-width pill that matches a [CheatButton]'s look. Opens the
 *  color wheel. Not a cheat (no id), so it does not share CheatDef /
 *  pending / toggle plumbing — just an onClick. */
@Composable
private fun CustomColorButton(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, accent, shape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.cheats_paint_custom_color),
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            maxLines = 2,
        )
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
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
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
            fontSize = 14.sp,
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
    // odd entry take a column of its own. The pane is narrower than
    // the full tab was pre-rail, but still comfortably fits 2 x
    // ~190 dp buttons on the 540 x 468 dp Thor bottom panel.
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
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            maxLines = 2,
        )
    }
}

/** Two-slide first-visit onboarding for the cheats tab. Shape and
 *  copy unchanged from the inline sheet this replaces; the slides
 *  are a plain list so the shared [OnboardingSheet] can render them.
 *  Kept here (rather than in a strings file) because the ordering
 *  is a UI decision, not a translation one. */
private val CHEATS_ONBOARDING_SLIDES = listOf(
    OnboardingSlide(
        titleRes = R.string.cheats_onboarding_slide1_title,
        bodyRes = R.string.cheats_onboarding_slide1_body,
    ),
    OnboardingSlide(
        titleRes = R.string.cheats_onboarding_slide2_title,
        bodyRes = R.string.cheats_onboarding_slide2_body,
    ),
)

private val RAIL_WIDTH = 100.dp
