package jr.brian.thorhud.ui.wardrobe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Wardrobe
import jr.brian.thorhud.ui.components.FullScreenSheet
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Wardrobe sheet (GTA V in this part; GTA IV picks it up in the
 * next). Opens on a long-press of the HUD's character-name card and
 * shows every clothing slot the current ped exposes — tops, legs,
 * feet, hair, accessories, props — plus a Saved Looks strip at the
 * top for one-tap re-apply of a stored outfit.
 *
 * Compose unconditionally with [visible] so the slide-out animation
 * plays on close. The sheet asks the plugin for a fresh catalogue on
 * open; the three-second grace period on exit protects against a
 * stall briefly dropping the ped out of the state frame.
 */
@Composable
internal fun WardrobeScreen(
    visible: Boolean,
    accent: Color,
    isGtaV: Boolean,
    characterLabel: String?,
    // The long-press is always live now; the sheet itself tells the
    // player to get out of the car when they open it in-vehicle,
    // instead of refusing to appear.
    isInVehicle: Boolean,
    onDismiss: () -> Unit,
    viewModel: WardrobeViewModel = hiltViewModel(),
) {
    val wardrobe by viewModel.wardrobe.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val title = characterLabel?.ifBlank { null }
        ?: stringResource(R.string.wardrobe_title)

    // Fire a wardrobe_list on open only when the user is actually on
    // foot — otherwise the plugin refuses and the sheet shows the
    // "get out of the car" placeholder.
    LaunchedEffect(visible, isInVehicle) {
        if (visible && !isInVehicle) viewModel.open()
    }

    val collapsedSections = rememberSaveable(saver = SetSaver) {
        mutableStateOf(emptySet<String>())
    }
    val toggleSection: (WardrobeSection) -> Unit = { section ->
        val cur = collapsedSections.value
        collapsedSections.value = if (section.name in cur) cur - section.name
                                   else cur + section.name
    }

    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    var presetMenuIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        accent = accent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            when {
                isInVehicle -> Note(
                    stringResource(R.string.wardrobe_get_out),
                )
                else -> {
                    SavedLooksStrip(
                        presets = presets,
                        canSave = canSavePreset(wardrobe, presets),
                        accent = accent,
                        onApply = viewModel::applyPreset,
                        onLongPress = { presetMenuIndex = it },
                        onSave = { showSaveSheet = true },
                    )
                    Spacer(Modifier.height(14.dp))

                    val w = wardrobe
                    if (w == null) {
                        EmptyPlaceholder()
                    } else {
                        val rows = remember(w, isGtaV) {
                            wardrobeRows(w, isGtaV)
                        }
                        for ((section, pieces) in rows.sections) {
                            SectionHeader(
                                section = section,
                                collapsed = section.name in collapsedSections.value,
                                accent = accent,
                                onToggle = { toggleSection(section) },
                            )
                            if (section.name !in collapsedSections.value) {
                                for (row in pieces) {
                                    PieceRow(
                                        slot = row.slot,
                                        isGtaV = isGtaV,
                                        pending = result?.pendingKey == pendingKey(row.slot),
                                        accent = accent,
                                        onNudgeStyle = { delta ->
                                            nudgeStyle(viewModel, row.slot, delta)
                                        },
                                        onNudgeColor = { delta ->
                                            nudgeColor(viewModel, row.slot, delta)
                                        },
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        ResetButton(onClick = { showResetConfirm = true },
                                    accent = accent)
                    }

                    val err = result?.error
                    if (!err.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            err,
                            color = Color(0xFFE57373),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.wardrobe_reset)) },
            text = { Text(stringResource(R.string.wardrobe_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    viewModel.resetOutfit()
                }) {
                    Text(stringResource(R.string.wardrobe_reset),
                         color = accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel),
                         color = Color(0xFF8EA096))
                }
            },
            containerColor = Color.Black,
        )
    }

    if (showSaveSheet) {
        NameDialog(
            title = stringResource(R.string.wardrobe_save_look),
            initial = "",
            accent = accent,
            onConfirm = { name ->
                showSaveSheet = false
                viewModel.savePreset(name)
            },
            onDismiss = { showSaveSheet = false },
        )
    }

    val menuIdx = presetMenuIndex
    if (menuIdx != null && menuIdx in presets.indices) {
        PresetMenuDialog(
            preset = presets[menuIdx],
            accent = accent,
            onRename = { newName ->
                viewModel.renamePreset(menuIdx, newName)
                presetMenuIndex = null
            },
            onDelete = {
                viewModel.deletePreset(menuIdx)
                presetMenuIndex = null
            },
            onDismiss = { presetMenuIndex = null },
        )
    }
}

@Composable
private fun EmptyPlaceholder() {
    Text(
        stringResource(R.string.wardrobe_loading),
        color = Color(0xFF8EA096),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 20.dp),
    )
}

/** Full-sheet message used when the state the player's in doesn't
 *  match what the sheet does (in-vehicle Wardrobe, on-foot Customs,
 *  etc.). Centred, grey, no interactive content. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Color(0xFFCFCFCF),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedLooksStrip(
    presets: List<WardrobePreset>,
    canSave: Boolean,
    accent: Color,
    onApply: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onSave: () -> Unit,
) {
    Text(
        stringResource(R.string.wardrobe_saved_looks),
        color = Color(0xFF8EA096),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((i, preset) in presets.withIndex()) {
            val source = remember(i) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, accent.copy(alpha = 0.4f),
                            RoundedCornerShape(14.dp))
                    .combinedClickable(
                        interactionSource = source,
                        indication = null,
                        onClick = { onApply(i) },
                        onLongClick = { onLongPress(i) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    preset.name,
                    color = Color(0xFFEDEDED),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
        if (canSave) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.18f))
                    .clickable(onClick = onSave)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.wardrobe_save_look),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    section: WardrobeSection,
    collapsed: Boolean,
    accent: Color,
    onToggle: () -> Unit,
) {
    val label = stringResource(sectionLabelRes(section))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight
                           else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = accent.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PieceRow(
    slot: GameState.WardrobeSlot,
    isGtaV: Boolean,
    pending: Boolean,
    accent: Color,
    onNudgeStyle: (Int) -> Unit,
    onNudgeColor: (Int) -> Unit,
) {
    val label = slotRowLabel(slot, isGtaV)
    val styleText = if (slot.prop && slot.cur < 0) {
        stringResource(R.string.wardrobe_none)
    } else {
        stringResource(R.string.wardrobe_style, slot.cur + 1, slot.styles)
    }
    val colorsForCur = slot.colors.getOrNull(slot.cur.coerceAtLeast(0)) ?: 0
    val canNudgeColor = !pending && colorsForCur > 1 && !(slot.prop && slot.cur < 0)
    val colorText = if (slot.prop && slot.cur < 0) ""
                     else stringResource(R.string.wardrobe_color,
                                         slot.tex + 1, colorsForCur)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0E0E0E))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NudgeArrow(
                left = true, enabled = !pending && slot.styles > 1,
                accent = accent, onClick = { onNudgeStyle(-1) },
            )
            Text(
                styleText,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            NudgeArrow(
                left = false, enabled = !pending && slot.styles > 1,
                accent = accent, onClick = { onNudgeStyle(1) },
            )
        }
        if (colorText.isNotEmpty()) {
            // Dedicated color row: ~12 dp gap under the style
            // arrows so the two ◀ ▶ pairs don't crowd each other
            // vertically — the color arrows are smaller, which
            // made the compressed layout read as one noisy block.
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NudgeArrow(
                    left = true, enabled = canNudgeColor, small = true,
                    accent = accent, onClick = { onNudgeColor(-1) },
                )
                Text(
                    colorText,
                    color = Color(0xFF8EA096),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                NudgeArrow(
                    left = false, enabled = canNudgeColor, small = true,
                    accent = accent, onClick = { onNudgeColor(1) },
                )
            }
        }
    }
}

@Composable
private fun NudgeArrow(
    left: Boolean,
    enabled: Boolean,
    accent: Color,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val size = if (small) 24.dp else 30.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = if (enabled) 0.22f else 0.08f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (left) Icons.AutoMirrored.Filled.KeyboardArrowLeft
                           else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled) accent else accent.copy(alpha = 0.3f),
            modifier = Modifier.size(size - 8.dp),
        )
    }
}

@Composable
private fun ResetButton(onClick: () -> Unit, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.wardrobe_reset),
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    accent: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.action_save), color = accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel),
                     color = Color(0xFF8EA096))
            }
        },
        containerColor = Color.Black,
    )
}

@Composable
private fun PresetMenuDialog(
    preset: WardrobePreset,
    accent: Color,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.wardrobe_rename),
            initial = preset.name,
            accent = accent,
            onConfirm = onRename,
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preset.name) },
        text = { Text(stringResource(R.string.wardrobe_preset_menu_body)) },
        confirmButton = {
            TextButton(onClick = { renaming = true }) {
                Text(stringResource(R.string.wardrobe_rename), color = accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.wardrobe_delete),
                     color = Color(0xFFE57373))
            }
        },
        containerColor = Color.Black,
    )
}

// --- helpers ---------------------------------------------------------

/** The pending-key used by the ViewModel for a given slot row. Any
 *  write on this slot stamps a matching key so the row greys out
 *  regardless of which style or color the user is nudging to. */
private fun pendingKey(slot: GameState.WardrobeSlot): String {
    val tag = if (slot.prop) "p" else "c"
    return "piece:$tag:${slot.slot}"
}

/** ◀ / ▶ on a style row. [delta] is +/- 1; on a prop slot, the
 *  "below zero" case wraps to the clear sentinel. */
private fun nudgeStyle(vm: WardrobeViewModel, slot: GameState.WardrobeSlot, delta: Int) {
    if (slot.styles <= 0) return
    val next = (slot.cur + delta).let { candidate ->
        if (slot.prop) {
            // Prop range: -1 .. styles-1 (inclusive). Wrap through -1.
            when {
                candidate < -1          -> slot.styles - 1
                candidate >= slot.styles -> -1
                else                     -> candidate
            }
        } else {
            ((candidate % slot.styles) + slot.styles) % slot.styles
        }
    }
    val tex = if (slot.prop && next < 0) 0 else 0 // land on texture 0 on a style flip
    vm.setPiece(packPiece(slot.prop, slot.slot, next, tex))
}

/** ◀ / ▶ on a color row. Only reachable when the current drawable
 *  actually has more than one texture — [PieceRow] disables the
 *  arrows otherwise. */
private fun nudgeColor(vm: WardrobeViewModel, slot: GameState.WardrobeSlot, delta: Int) {
    val cur = slot.cur.coerceAtLeast(0)
    val colorCount = slot.colors.getOrNull(cur) ?: 0
    if (colorCount <= 1) return
    val next = ((slot.tex + delta) % colorCount + colorCount) % colorCount
    vm.setPiece(packPiece(slot.prop, slot.slot, slot.cur, next))
}

private fun sectionLabelRes(section: WardrobeSection): Int = when (section) {
    WardrobeSection.Tops        -> R.string.wardrobe_section_tops
    WardrobeSection.Legs        -> R.string.wardrobe_section_legs
    WardrobeSection.Feet        -> R.string.wardrobe_section_feet
    WardrobeSection.Hair        -> R.string.wardrobe_section_hair
    WardrobeSection.Accessories -> R.string.wardrobe_section_accessories
    WardrobeSection.Props       -> R.string.wardrobe_section_props
    WardrobeSection.Other       -> R.string.wardrobe_section_other
}

/** Row label for a slot. [slotLabelRes] has a per-game, per-id entry
 *  for every slot the walker publishes on SP protagonists; unknown
 *  slot ids (Director Mode, mods) fall back to the generic
 *  "slot N" / "prop N" so a surprising row still reads sensibly. */
@Composable
private fun slotRowLabel(
    slot: GameState.WardrobeSlot,
    isGtaV: Boolean,
): String {
    val res = slotLabelRes(isGtaV, slot.prop, slot.slot)
    return if (res != 0) {
        stringResource(res)
    } else if (slot.prop) {
        stringResource(R.string.wardrobe_prop_n, slot.slot)
    } else {
        stringResource(R.string.wardrobe_slot_n, slot.slot)
    }
}

/** rememberSaveable's default saver cannot hold a Set; the Customs
 *  sheet uses the same trick. */
private val SetSaver = Saver<androidx.compose.runtime.MutableState<Set<String>>, String>(
    save = { it.value.joinToString(",") },
    restore = { stored ->
        mutableStateOf(
            if (stored.isEmpty()) emptySet()
            else stored.split(",").toSet(),
        )
    },
)
