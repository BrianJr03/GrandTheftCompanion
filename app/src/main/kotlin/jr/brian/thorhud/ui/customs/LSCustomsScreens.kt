package jr.brian.thorhud.ui.customs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Mods
import jr.brian.thorhud.ui.cheats.ColorWheelSheet
import jr.brian.thorhud.ui.cheats.PAINT_SWATCHES
import jr.brian.thorhud.ui.cheats.unpackRgb
import jr.brian.thorhud.ui.components.FullScreenSheet
import kotlinx.coroutines.delay

/**
 * The Los Santos Customs sheet (GTA V only). Opens on a long-press of
 * the HUD's vehicle card and shows every mod a shop would sell for
 * the current car — body, performance, wheels, interior, lights,
 * plate, tint, neon, bulletproof tyres — plus a Paint section that
 * reuses the swatch row + colour wheel from the Cheats tab.
 *
 * Compose unconditionally with [visible] so the slide-out animation
 * plays on close. The sheet asks the plugin for a fresh catalogue
 * on open; the three-second grace period on exit protects against a
 * stall momentarily dropping the vehicle object out of the state
 * frame.
 */
@Composable
internal fun LSCustomsScreens(
    visible: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    viewModel: CustomsViewModel = hiltViewModel(),
) {
    val mods by viewModel.mods.collectAsStateWithLifecycle()
    val state by viewModel.vehicle.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val vehicle = state?.vehicle?.takeIf { state?.inVehicle == true }
    val title = vehicle?.name?.ifBlank { null }
        ?: stringResource(R.string.customs_title)

    // 3-second grace: a stall that briefly drops the vehicle object
    // should not slam the sheet shut. If the plugin keeps reporting
    // on-foot for longer than that, dismiss.
    LaunchedEffect(visible, state?.inVehicle) {
        if (visible && state?.inVehicle == false) {
            delay(3000)
            if (state?.inVehicle == false) onDismiss()
        }
    }
    LaunchedEffect(visible) {
        if (visible) viewModel.open()
    }

    val collapsedSections = rememberSaveable(saver = SetSaver) {
        mutableStateOf(emptySet<String>())
    }
    val toggleSection: (CustomsSection) -> Unit = { section ->
        val current = collapsedSections.value
        collapsedSections.value = if (section.name in current) current - section.name
                                  else current + section.name
    }

    var showColorWheel by rememberSaveable { mutableStateOf(false) }
    var lastWheelPicked by rememberSaveable { mutableStateOf<Long?>(null) }

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        accent = accent,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    vehicle == null -> Text(
                        stringResource(R.string.customs_get_in_car),
                        color = Color(0xFFCFCFCF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    mods?.kit == false -> Text(
                        stringResource(R.string.customs_no_kit),
                        color = Color(0xFFCFCFCF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    else -> CustomsBody(
                        accent = accent,
                        mods = mods,
                        pendingKey = result?.takeIf { it.error == null }?.pendingKey,
                        collapsedSections = collapsedSections.value,
                        onToggleSection = toggleSection,
                        onRunCheat = viewModel::runCheat,
                        onPaintRgb = { packed ->
                            lastWheelPicked = packed
                            viewModel.paintRgb(packed)
                        },
                        onOpenWheel = { showColorWheel = true },
                        onSetMod = viewModel::setMod,
                        onToggleMod = viewModel::toggleMod,
                        onSetWheelType = viewModel::setWheelType,
                        onSetTint = viewModel::setTint,
                        onSetPlateStyle = viewModel::setPlateStyle,
                        onSetPlate = viewModel::setPlate,
                        onSetNeon = viewModel::setNeon,
                        onSetSmoke = viewModel::setSmoke,
                        onSetXenonColour = viewModel::setXenonColour,
                        onSetBulletproofTyres = viewModel::setBulletproofTyres,
                    )
                }
                val err = result?.error
                if (!err.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.customs_error, err),
                        color = Color(0xFFE07070),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    // Composed unconditionally so the slide-out animation plays on
    // dismiss. Same contract as the cheats-tab wheel: the picked RGB
    // rides through onPaintRgb.
    ColorWheelSheet(
        visible = showColorWheel,
        accent = accent,
        initial = lastWheelPicked,
        onDismiss = { showColorWheel = false },
        onPick = { packed ->
            lastWheelPicked = packed
            viewModel.paintRgb(packed)
        },
    )
}

/** rememberSaveable's default saver cannot hold a Set; a tiny custom
 *  saver stores the comma-joined section names so collapses survive
 *  rotation. */
private val SetSaver = androidx.compose.runtime.saveable.Saver<
    androidx.compose.runtime.MutableState<Set<String>>, String>(
    save = { it.value.joinToString(",") },
    restore = { stored ->
        androidx.compose.runtime.mutableStateOf(
            if (stored.isEmpty()) emptySet()
            else stored.split(",").toSet()
        )
    },
)

@Composable
private fun CustomsBody(
    accent: Color,
    mods: Mods?,
    pendingKey: String?,
    collapsedSections: Set<String>,
    onToggleSection: (CustomsSection) -> Unit,
    onRunCheat: (Int) -> Unit,
    onPaintRgb: (Long) -> Unit,
    onOpenWheel: () -> Unit,
    onSetMod: (slot: Int, index: Int) -> Unit,
    onToggleMod: (slot: Int) -> Unit,
    onSetWheelType: (type: Int) -> Unit,
    onSetTint: (tint: Int) -> Unit,
    onSetPlateStyle: (style: Int) -> Unit,
    onSetPlate: (text: String) -> Unit,
    onSetNeon: (packedRgb: Long) -> Unit,
    onSetSmoke: (packedRgb: Long) -> Unit,
    onSetXenonColour: (index: Int) -> Unit,
    onSetBulletproofTyres: (on: Boolean) -> Unit,
) {
    val m = mods
    // Paint section first — functionally the shop's till row. Always
    // visible, even before the walker has returned the mods frame.
    PaintSection(
        accent = accent,
        pendingKey = pendingKey,
        onRunCheat = onRunCheat,
        onOpenWheel = onOpenWheel,
        onPaintRgb = onPaintRgb,
    )

    if (m == null) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.customs_loading),
            color = Color(0xFF8EA096),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        return
    }

    val rows = customsRows(m)
    for ((section, rowsInSection) in rows.sections) {
        if (section == CustomsSection.Paint) continue
        SectionHeader(
            section = section,
            accent = accent,
            collapsed = section.name in collapsedSections,
            onClick = { onToggleSection(section) },
        )
        if (section.name !in collapsedSections) {
            // Dedicated rows that live under their logical sections
            // but come from the top-level Mods struct, not the
            // slot[] walk.
            if (section == CustomsSection.Wheels) {
                WheelTypeRow(
                    accent = accent,
                    wheelType = m.wheelType,
                    pending = pendingKey == "set_wheel_type",
                    onSet = onSetWheelType,
                )
            }
            for (row in rowsInSection) when (row) {
                is CustomsRow.SlotRow -> SlotArrowRow(
                    accent = accent,
                    slot = row.slot,
                    pending = pendingKey == "set_mod:${row.slot.slot}",
                    onSet = onSetMod,
                )
                is CustomsRow.ToggleRow -> ToggleSwitchRow(
                    accent = accent,
                    toggle = row.toggle,
                    pending = pendingKey == "toggle_mod:${row.toggle.slot}",
                    onToggle = onToggleMod,
                )
            }
            // Section-wide extras that are not per-slot (tint, plate,
            // neon colour, smoke colour, bulletproof) sit under their
            // logical sections rather than own headers.
            when (section) {
                CustomsSection.Plate -> {
                    PlateStyleRow(
                        accent = accent,
                        style = m.plateStyle,
                        pending = pendingKey == "set_plate_style",
                        onSet = onSetPlateStyle,
                    )
                    PlateTextRow(
                        accent = accent,
                        currentPlate = m.plate,
                        pending = pendingKey == "set_plate",
                        onSet = onSetPlate,
                    )
                }
                CustomsSection.LightsExtras -> {
                    TintRow(
                        accent = accent,
                        tint = m.tint,
                        tints = m.tints,
                        pending = pendingKey == "set_tint",
                        onSet = onSetTint,
                    )
                    NeonRow(
                        accent = accent,
                        enabled = m.neon,
                        pending = pendingKey == "set_neon",
                        onSet = onSetNeon,
                    )
                    SmokeRow(
                        accent = accent,
                        pending = pendingKey == "set_smoke",
                        onSet = onSetSmoke,
                    )
                    XenonRow(
                        accent = accent,
                        pending = pendingKey == "set_xenon_colour",
                        onSet = onSetXenonColour,
                    )
                    BulletproofRow(
                        accent = accent,
                        on = m.bulletproof,
                        pending = pendingKey == "set_bulletproof_tyres",
                        onSet = onSetBulletproofTyres,
                    )
                }
                else -> Unit
            }
        }
    }
    // Fallback: plate/lights sections never appeared in the slot walk
    // but still want their fixed rows (tint applies to every car).
    // When the walker returns no LightsExtras entries, render those
    // rows under a synthesised header so the player still has access.
    val sectionNames = rows.sections.map { it.first }.toSet()
    if (CustomsSection.LightsExtras !in sectionNames) {
        SectionHeader(
            section = CustomsSection.LightsExtras,
            accent = accent,
            collapsed = CustomsSection.LightsExtras.name in collapsedSections,
            onClick = { onToggleSection(CustomsSection.LightsExtras) },
        )
        if (CustomsSection.LightsExtras.name !in collapsedSections) {
            TintRow(accent, m.tint, m.tints, pendingKey == "set_tint", onSetTint)
            NeonRow(accent, m.neon, pendingKey == "set_neon", onSetNeon)
//            SmokeRow(accent, pendingKey == "set_smoke", onSetSmoke)
            XenonRow(accent, pendingKey == "set_xenon_colour", onSetXenonColour)
            BulletproofRow(accent, m.bulletproof,
                           pendingKey == "set_bulletproof_tyres",
                           onSetBulletproofTyres)
        }
    }
    if (CustomsSection.Plate !in sectionNames) {
        SectionHeader(
            section = CustomsSection.Plate,
            accent = accent,
            collapsed = CustomsSection.Plate.name in collapsedSections,
            onClick = { onToggleSection(CustomsSection.Plate) },
        )
        if (CustomsSection.Plate.name !in collapsedSections) {
            PlateStyleRow(accent, m.plateStyle,
                          pendingKey == "set_plate_style", onSetPlateStyle)
            PlateTextRow(accent, m.plate, pendingKey == "set_plate", onSetPlate)
        }
    }
    if (CustomsSection.Wheels !in sectionNames) {
        SectionHeader(
            section = CustomsSection.Wheels,
            accent = accent,
            collapsed = CustomsSection.Wheels.name in collapsedSections,
            onClick = { onToggleSection(CustomsSection.Wheels) },
        )
        if (CustomsSection.Wheels.name !in collapsedSections) {
            WheelTypeRow(accent, m.wheelType,
                         pendingKey == "set_wheel_type", onSetWheelType)
        }
    }
}

@Composable
private fun SectionHeader(
    section: CustomsSection,
    accent: Color,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(sectionLabelRes(section))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (collapsed) "▸" else "▾",
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
    }
}

private fun sectionLabelRes(section: CustomsSection): Int = when (section) {
    CustomsSection.Paint        -> R.string.customs_section_paint
    CustomsSection.Body         -> R.string.customs_section_body
    CustomsSection.Performance  -> R.string.customs_section_performance
    CustomsSection.Wheels       -> R.string.customs_section_wheels
    CustomsSection.Interior     -> R.string.customs_section_interior
    CustomsSection.LightsExtras -> R.string.customs_section_lights
    CustomsSection.Plate        -> R.string.customs_section_plate
    CustomsSection.Other        -> R.string.customs_section_other
}

@Composable
private fun SlotArrowRow(
    accent: Color,
    slot: GameState.ModSlot,
    pending: Boolean,
    onSet: (slot: Int, index: Int) -> Unit,
) {
    // Current label and the ◀ / ▶ wrap-through-stock indices. Stock is
    // -1, so cur = -1 shows "stock"; previous wraps -1 to the last
    // option, next wraps the last option to -1.
    val n = slot.options.size
    val curLabel = optionLabel(slot, slot.cur)
    val slotName = slot.name.ifBlank { fallbackSlotName(slot.slot) }
    ArrowRow(
        label = slotName,
        value = curLabel,
        accent = accent,
        pending = pending,
        onPrev = { onSet(slot.slot, prevIndex(slot.cur, n)) },
        onNext = { onSet(slot.slot, nextIndex(slot.cur, n)) },
    )
}

private fun prevIndex(cur: Int, n: Int): Int = when {
    n <= 0        -> STOCK
    cur == STOCK  -> n - 1
    cur == 0      -> STOCK
    else          -> cur - 1
}

private fun nextIndex(cur: Int, n: Int): Int = when {
    n <= 0        -> STOCK
    cur == STOCK  -> 0
    cur == n - 1  -> STOCK
    else          -> cur + 1
}

@Composable
private fun optionLabel(slot: GameState.ModSlot, idx: Int): String {
    if (idx == STOCK) return stringResource(R.string.customs_stock)
    val label = slot.options.getOrNull(idx).orEmpty()
    if (label.isNotBlank()) return label
    // Game returned a GXT key with no entry. Pick a better fallback
    // than "Option N": performance slots have canonical level names
    // (verified against the FiveM / altMP mod references), everything
    // else gets "<slot name> <N>" so "Spoiler 2" reads clearly in
    // place of "Option 2".
    canonicalOptionLabel(slot.slot, idx)?.let { return it }
    val slotName = slot.name.ifBlank { fallbackSlotName(slot.slot) }
    return stringResource(R.string.customs_option_variant, slotName, idx + 1)
}

/**
 * Canonical option labels for the performance slots whose indices
 * are fixed across every car in the base game. Sources checked:
 * altMP vehicle-mods docs (value ranges), FiveM native docs
 * (option counts), community mod guides (level naming). Returns
 * null for slots whose options are per-car (body / wheels / etc.).
 */
@Composable
private fun canonicalOptionLabel(slot: Int, idx: Int): String? = when (slot) {
    // Engine (11): 0..4. 0 is EMS Level 1 up through Level 5 at idx 4;
    // most cars only expose through Level 4 and GET_NUM_VEHICLE_MODS
    // caps the walk.
    11 -> stringResource(R.string.customs_opt_engine_level, idx + 1)
    // Brakes (12): 0=Street, 1=Sport, 2=Race.
    12 -> when (idx) {
        0 -> stringResource(R.string.customs_opt_brakes_street)
        1 -> stringResource(R.string.customs_opt_brakes_sport)
        2 -> stringResource(R.string.customs_opt_brakes_race)
        else -> null
    }
    // Transmission (13): 0=Street, 1=Sports, 2=Race.
    13 -> when (idx) {
        0 -> stringResource(R.string.customs_opt_trans_street)
        1 -> stringResource(R.string.customs_opt_trans_sports)
        2 -> stringResource(R.string.customs_opt_trans_race)
        else -> null
    }
    // Suspension (15): 0=Lowered, 1=Street, 2=Sport, 3=Competition, 4=Race.
    15 -> when (idx) {
        0 -> stringResource(R.string.customs_opt_susp_lowered)
        1 -> stringResource(R.string.customs_opt_susp_street)
        2 -> stringResource(R.string.customs_opt_susp_sport)
        3 -> stringResource(R.string.customs_opt_susp_competition)
        4 -> stringResource(R.string.customs_opt_susp_race)
        else -> null
    }
    // Armor (16): 20% / 40% / 60% / 80% / 100% (0..4).
    16 -> stringResource(R.string.customs_opt_armor_pct, (idx + 1) * 20)
    else -> null
}

/**
 * Rockstar's eVehicleModType enum (0..48) mapped to human-readable
 * labels. Only used when the game's own GET_MOD_SLOT_NAME returned
 * a key with no GXT entry — most shops will give us the game's own
 * label (e.g. "CMOD_MOD_SPO" -> "Spoilers") via GET_LABEL_TEXT and
 * the walker publishes that string directly. For the handful that
 * resolve to "NULL", these names come from community references:
 * FiveM natives docs, scripthookvdotnet wiki, Dkamps18/GTA-Cheat-Sheet.
 */
@Composable
private fun fallbackSlotName(slot: Int): String {
    val resId = when (slot) {
        0  -> R.string.customs_slot_spoiler
        1  -> R.string.customs_slot_front_bumper
        2  -> R.string.customs_slot_rear_bumper
        3  -> R.string.customs_slot_side_skirt
        4  -> R.string.customs_slot_exhaust
        5  -> R.string.customs_slot_frame
        6  -> R.string.customs_slot_grille
        7  -> R.string.customs_slot_hood
        8  -> R.string.customs_slot_left_fender
        9  -> R.string.customs_slot_right_fender
        10 -> R.string.customs_slot_roof
        11 -> R.string.customs_slot_engine
        12 -> R.string.customs_slot_brakes
        13 -> R.string.customs_slot_transmission
        14 -> R.string.customs_slot_horn
        15 -> R.string.customs_slot_suspension
        16 -> R.string.customs_slot_armor
        17 -> R.string.customs_slot_nitrous
        // 18 (turbo), 20 (tyre smoke), 22 (xenon) are toggles — they
        // reach the renderer through ToggleSwitchRow, not here.
        19 -> R.string.customs_slot_subwoofer
        21 -> R.string.customs_slot_hydraulics_19
        23 -> R.string.customs_slot_front_wheels
        24 -> R.string.customs_slot_rear_wheels
        25 -> R.string.customs_slot_plate_holder
        26 -> R.string.customs_slot_vanity_plate
        27 -> R.string.customs_slot_trim
        28 -> R.string.customs_slot_ornaments
        29 -> R.string.customs_slot_dashboard
        30 -> R.string.customs_slot_dial
        31 -> R.string.customs_slot_door_speaker
        32 -> R.string.customs_slot_seats
        33 -> R.string.customs_slot_steering_wheel
        34 -> R.string.customs_slot_shifter
        35 -> R.string.customs_slot_plaques
        36 -> R.string.customs_slot_speakers
        37 -> R.string.customs_slot_trunk
        38 -> R.string.customs_slot_hydraulics_38
        39 -> R.string.customs_slot_engine_block
        40 -> R.string.customs_slot_air_filter
        41 -> R.string.customs_slot_struts
        42 -> R.string.customs_slot_arch_cover
        43 -> R.string.customs_slot_aerials
        44 -> R.string.customs_slot_trim_2
        45 -> R.string.customs_slot_tank
        46 -> R.string.customs_slot_left_door
        47 -> R.string.customs_slot_right_door
        48 -> R.string.customs_slot_livery
        else -> R.string.customs_slot_n
    }
    return if (resId == R.string.customs_slot_n) stringResource(resId, slot)
    else stringResource(resId)
}

@Composable
private fun ToggleSwitchRow(
    accent: Color,
    toggle: GameState.ModToggle,
    pending: Boolean,
    onToggle: (slot: Int) -> Unit,
) {
    val name = toggle.name.ifBlank {
        stringResource(when (toggle.slot) {
            18 -> R.string.customs_toggle_turbo
            20 -> R.string.customs_toggle_tyre_smoke
            22 -> R.string.customs_toggle_xenon
            else -> R.string.customs_slot_n
        }, toggle.slot)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = toggle.on,
            onCheckedChange = { onToggle(toggle.slot) },
            enabled = !pending,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = accent,
                uncheckedThumbColor = accent,
                uncheckedTrackColor = Color.Black,
                uncheckedBorderColor = accent,
            ),
        )
    }
}

@Composable
private fun WheelTypeRow(
    accent: Color,
    wheelType: Int,
    pending: Boolean,
    onSet: (type: Int) -> Unit,
) {
    val names = listOf(
        R.string.customs_wheel_type_sport,
        R.string.customs_wheel_type_muscle,
        R.string.customs_wheel_type_lowrider,
        R.string.customs_wheel_type_suv,
        R.string.customs_wheel_type_offroad,
        R.string.customs_wheel_type_tuner,
        R.string.customs_wheel_type_bike,
        R.string.customs_wheel_type_highend,
    )
    val labelRes = names.getOrNull(wheelType) ?: R.string.customs_wheel_type_other
    ArrowRow(
        label = stringResource(R.string.customs_wheel_type),
        value = stringResource(labelRes),
        accent = accent,
        pending = pending,
        onPrev = { onSet(((wheelType - 1) + names.size) % names.size) },
        onNext = { onSet((wheelType + 1) % names.size) },
    )
}

@Composable
private fun TintRow(
    accent: Color,
    tint: Int,
    tints: Int,
    pending: Boolean,
    onSet: (tint: Int) -> Unit,
) {
    val count = tints.coerceAtLeast(1)
    ArrowRow(
        label = stringResource(R.string.customs_tint),
        value = stringResource(R.string.customs_option_n, tint + 1),
        accent = accent,
        pending = pending,
        onPrev = { onSet(((tint - 1) + count) % count) },
        onNext = { onSet((tint + 1) % count) },
    )
}

@Composable
private fun PlateStyleRow(
    accent: Color,
    style: Int,
    pending: Boolean,
    onSet: (style: Int) -> Unit,
) {
    // Six base-game plate styles per NativeDB; the mod game may add
    // more but writing past 5 is rejected at the plugin boundary.
    val count = 6
    ArrowRow(
        label = stringResource(R.string.customs_plate_style),
        value = stringResource(R.string.customs_option_n, style + 1),
        accent = accent,
        pending = pending,
        onPrev = { onSet(((style - 1) + count) % count) },
        onNext = { onSet((style + 1) % count) },
    )
}

@Composable
private fun PlateTextRow(
    accent: Color,
    currentPlate: String,
    pending: Boolean,
    onSet: (text: String) -> Unit,
) {
    var text by rememberSaveable(currentPlate) { mutableStateOf(currentPlate) }
    val valid = isValidPlateText(text)
    // Apply drops focus so the IME closes and the cursor stops
    // blinking in the field — the write has gone to the plugin, no
    // reason to leave the keyboard up.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.customs_plate),
            color = Color(0xFF8EA096),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    text = raw.uppercase().take(8).filter { c ->
                        (c in 'A'..'Z') || (c in '0'..'9') || c == ' '
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(0xFFEDEDED),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                ),
                placeholder = {
                    Text(
                        stringResource(R.string.customs_plate_hint),
                        color = Color(0xFF6A7A6E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = accent.copy(alpha = 0.6f),
                    cursorColor = accent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = {
                    onSet(text)
                    keyboard?.hide()
                    focusManager.clearFocus(force = true)
                },
                enabled = valid && !pending,
            ) {
                Text(
                    stringResource(R.string.customs_plate_apply),
                    color = if (valid) accent else Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun NeonRow(
    accent: Color,
    enabled: Boolean,
    pending: Boolean,
    onSet: (packedRgb: Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.customs_neon),
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onSet(0L) },
                enabled = enabled && !pending,
            ) {
                Text(
                    stringResource(R.string.customs_off),
                    color = if (enabled) accent else Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
        SwatchStrip(accent = accent, pending = pending, onPick = onSet)
    }
}

@Composable
private fun SmokeRow(
    accent: Color,
    pending: Boolean,
    onSet: (packedRgb: Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.customs_smoke),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        SwatchStrip(accent = accent, pending = pending, onPick = onSet)
    }
}

@Composable
private fun SwatchStrip(
    accent: Color,
    pending: Boolean,
    onPick: (packedRgb: Long) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (sw in PAINT_SWATCHES) {
            val (r, g, b) = unpackRgb(sw.packed)
            val fill = Color(r, g, b).copy(alpha = if (pending) 0.4f else 1f)
            val cd = stringResource(sw.labelRes)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(fill)
                    .border(1.dp, accent, RoundedCornerShape(6.dp))
                    .semantics { contentDescription = cd }
                    .clickable(enabled = !pending) { onPick(sw.packed) },
            )
        }
    }
}

@Composable
private fun XenonRow(
    accent: Color,
    pending: Boolean,
    onSet: (index: Int) -> Unit,
) {
    // Xenon colour indices 0..12 (prediction, pinned on-device). The
    // row paints 13 small coloured dots the player can tap; -1
    // (default blue) is the first "default" pill.
    val count = 13
    var idx by rememberSaveable { mutableStateOf(-1) }
    val label = if (idx == -1) stringResource(R.string.customs_default)
                else stringResource(R.string.customs_option_n, idx + 1)
    ArrowRow(
        label = stringResource(R.string.customs_xenon),
        value = label,
        accent = accent,
        pending = pending,
        onPrev = {
            idx = if (idx <= -1) count - 1 else idx - 1
            onSet(idx)
        },
        onNext = {
            idx = if (idx >= count - 1) -1 else idx + 1
            onSet(idx)
        },
    )
}

@Composable
private fun BulletproofRow(
    accent: Color,
    on: Boolean,
    pending: Boolean,
    onSet: (on: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.customs_bulletproof),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on,
            onCheckedChange = onSet,
            enabled = !pending,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = accent,
                uncheckedThumbColor = accent,
                uncheckedTrackColor = Color.Black,
                uncheckedBorderColor = accent,
            ),
        )
    }
}

@Composable
private fun ArrowRow(
    label: String,
    value: String,
    accent: Color,
    pending: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                value,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        ArrowIconButton(
            icon = Icons.Filled.KeyboardArrowLeft,
            accent = accent, pending = pending, onClick = onPrev,
        )
        Spacer(Modifier.size(4.dp))
        ArrowIconButton(
            icon = Icons.Filled.KeyboardArrowRight,
            accent = accent, pending = pending, onClick = onNext,
        )
    }
}

@Composable
private fun ArrowIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (pending) 0.4f else 1f
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, accent.copy(alpha = alpha), RoundedCornerShape(50))
            .clickable(enabled = !pending, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent.copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Paint section at the top of the sheet. Three cheat buttons
 * (respray / next colour / swap) call runCheat; the colour wheel and
 * swatch row call paintRgb. The second row pairs the colour wheel
 * with a Fix vehicle button (cheat id 6) — same cheat the shop would
 * fire on a respray, exposed on its own so a battered car gets a
 * clean slate without a palette round-trip.
 */
@Composable
private fun PaintSection(
    accent: Color,
    pendingKey: String?,
    onRunCheat: (Int) -> Unit,
    onOpenWheel: () -> Unit,
    onPaintRgb: (Long) -> Unit,
) {
    SectionHeader(
        section = CustomsSection.Paint,
        accent = accent,
        collapsed = false,
        onClick = {},
    )
    val buttons = listOf(
        Triple(60, R.string.customs_paint_respray, "cheat:60"),
        Triple(61, R.string.customs_paint_next, "cheat:61"),
        Triple(62, R.string.customs_paint_swap, "cheat:62"),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((id, label, key) in buttons) {
            PillButton(
                label = stringResource(label),
                accent = accent,
                pending = pendingKey == key,
                onClick = { onRunCheat(id) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PillButton(
            label = stringResource(R.string.customs_paint_wheel),
            accent = accent,
            pending = pendingKey == "paint_rgb",
            onClick = onOpenWheel,
            modifier = Modifier.weight(1f),
        )
        PillButton(
            label = stringResource(R.string.customs_fix_vehicle),
            accent = accent,
            pending = pendingKey == "cheat:6",
            onClick = { onRunCheat(6) },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    SwatchStrip(
        accent = accent,
        pending = pendingKey == "paint_rgb",
        onPick = onPaintRgb,
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PillButton(
    label: String,
    accent: Color,
    pending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val alpha = if (pending) 0.5f else 1f
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = alpha))
            .border(1.dp, accent.copy(alpha = alpha), shape)
            .clickable(enabled = !pending, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = accent.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            maxLines = 2,
        )
    }
}
