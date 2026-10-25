package jr.brian.thorhud.ui.ammunation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.model.Armory
import jr.brian.thorhud.model.ArmoryGroup
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.FullScreenSheet
import jr.brian.thorhud.ui.v.vWheelSlotOf
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Ammu-Nation sheet (GTA V only). Opens on a long-press of the
 * on-foot weapon card.
 *
 * Layout: two columns. Left is a vertical rail of every owned weapon
 * grouped by weapon-wheel slot (pistols / SMGs / rifles / snipers /
 * melee / shotguns / heavy / thrown), each with a tap-to-collapse
 * header. Right pane is the current weapon's Attachments / Tint /
 * Ammo. Both columns scroll independently so a long rail does not
 * fight the details page.
 *
 * Behaviour follows the user's `autoSelectEquipped` preference
 * (default on): the sheet opens on the currently-equipped weapon and
 * follows weapon changes in-game. Off: the sheet opens on the first
 * owned weapon of the first non-empty section and stays wherever the
 * user tapped last.
 */
@Composable
internal fun AmmuNationScreen(
    visible: Boolean,
    accent: Color,
    autoSelectEquipped: Boolean,
    onDismiss: () -> Unit,
    viewModel: AmmuNationViewModel = hiltViewModel(),
) {
    val armory by viewModel.armory.collectAsStateWithLifecycle()
    val weapons by viewModel.weapons.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val onFoot = state?.inVehicle == false

    // 3-second grace: a stall that briefly drops on-foot should not
    // slam the sheet shut. Hop out if the plugin says in-vehicle for
    // longer — the plugin refuses our commands anyway.
    LaunchedEffect(visible, state?.inVehicle) {
        if (visible && state?.inVehicle == true) {
            delay(3000.milliseconds)
            onDismiss()
        }
    }

    // Target weapon selection:
    //   - autoSelectEquipped on: pin to state.weaponHash, so a weapon
    //     switch in-game while the sheet is open moves the rail with
    //     the player.
    //   - autoSelectEquipped off: open on the first owned weapon and
    //     stay there until the user picks another in the rail.
    // weapons must contain the hash — the state frame may report a
    // weapon the plugin hasn't yet published in the owned list.
    val equippedHash = state?.weaponHash
    LaunchedEffect(visible, autoSelectEquipped, equippedHash, weapons.size,
                   armory?.weaponHash) {
        if (!visible) return@LaunchedEffect
        val desired = when {
            autoSelectEquipped && equippedHash != null && equippedHash != 0L
                && weapons.any { it.hash == equippedHash } -> equippedHash
            armory == null -> weapons.firstOrNull()?.hash
            else -> null
        }
        if (desired != null && desired != armory?.weaponHash) {
            viewModel.open(desired)
        }
    }

    // Rail accordions start fully collapsed: 54 owned weapons across
    // eight slots do not fit even with the smallest chip height, and
    // the rail is where the user picks a weapon rather than where
    // they want to see everything at once. expandedSections flips
    // membership → visibility; a tap on a header adds its key.
    val expandedRail = rememberSaveable(saver = SetSaver) {
        mutableStateOf(emptySet<String>())
    }
    val toggleRailSection: (String) -> Unit = { s ->
        expandedRail.value =
            if (s in expandedRail.value) expandedRail.value - s
            else expandedRail.value + s
    }
    // Details pane keeps the collapsed-set shape: Attachments / Tint
    // / Ammo start expanded so the first thing the user sees after
    // picking a weapon is the content they came for.
    val collapsedDetails = rememberSaveable(saver = SetSaver) {
        mutableStateOf(emptySet<String>())
    }
    val toggleDetailsSection: (String) -> Unit = { s ->
        collapsedDetails.value =
            if (s in collapsedDetails.value) collapsedDetails.value - s
            else collapsedDetails.value + s
    }

    val title = armory?.name?.ifBlank { null }
        ?: stringResource(R.string.ammu_title_fallback)

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        accent = accent,
    ) {
        when {
            !onFoot -> Note(stringResource(R.string.ammu_get_on_foot))
            weapons.isEmpty() -> Note(stringResource(R.string.ammu_get_armed))
            else -> SplitLayout(
                accent = accent,
                armory = armory,
                weapons = weapons,
                pendingKey = result?.takeIf { it.error == null }?.pendingKey,
                expandedRail = expandedRail.value,
                onToggleRailSection = toggleRailSection,
                collapsedDetails = collapsedDetails.value,
                onToggleDetailsSection = toggleDetailsSection,
                onSelectWeapon = viewModel::selectWeapon,
                onSetComponent = viewModel::setComponent,
                onSetWeaponTint = viewModel::setWeaponTint,
                onFillAmmo = viewModel::fillAmmo,
                onAddClip = viewModel::addClip,
            )
        }
        val err = result?.error
        if (!err.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ammu_error, err),
                color = Color(0xFFE07070),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun SplitLayout(
    accent: Color,
    armory: Armory?,
    weapons: List<GameState.VWeapon>,
    pendingKey: String?,
    expandedRail: Set<String>,
    onToggleRailSection: (String) -> Unit,
    collapsedDetails: Set<String>,
    onToggleDetailsSection: (String) -> Unit,
    onSelectWeapon: (Long) -> Unit,
    onSetComponent: (widx: Int, cidx: Int, on: Boolean) -> Unit,
    onSetWeaponTint: (widx: Int, tint: Int) -> Unit,
    onFillAmmo: (widx: Int) -> Unit,
    onAddClip: (widx: Int) -> Unit,
) {
    val rail = railGroupsFor(weapons)
    val selectedHash = armory?.weaponHash ?: 0L
    Row(modifier = Modifier.fillMaxSize()) {
        WeaponRail(
            accent = accent,
            rail = rail,
            selectedHash = selectedHash,
            expandedSections = expandedRail,
            onToggleSection = onToggleRailSection,
            onSelect = onSelectWeapon,
            modifier = Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                // Trailing padding so the per-section weapon count
                // doesn't kiss the vertical divider to its right.
                .padding(end = 8.dp),
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
                .padding(start = 10.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DetailsPane(
                accent = accent,
                armory = armory,
                pendingKey = pendingKey,
                collapsedSections = collapsedDetails,
                onToggleSection = onToggleDetailsSection,
                onSetComponent = onSetComponent,
                onSetWeaponTint = onSetWeaponTint,
                onFillAmmo = onFillAmmo,
                onAddClip = onAddClip,
            )
        }
    }
}

@Composable
private fun WeaponRail(
    accent: Color,
    rail: List<RailSection>,
    selectedHash: Long,
    expandedSections: Set<String>,
    onToggleSection: (String) -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for (section in rail) {
            val expanded = section.key in expandedSections
            SectionHeader(
                title = section.title,
                count = section.weapons.size,
                accent = accent,
                collapsed = !expanded,
                onClick = { onToggleSection(section.key) },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    for (w in section.weapons) {
                        RailChip(
                            label = displayNameOf(w),
                            selected = w.hash == selectedHash,
                            accent = accent,
                            onClick = { onSelect(w.hash) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    accent: Color,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight
                          else Icons.Filled.KeyboardArrowDown,
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
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
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

@Composable
private fun DetailsPane(
    accent: Color,
    armory: Armory?,
    pendingKey: String?,
    collapsedSections: Set<String>,
    onToggleSection: (String) -> Unit,
    onSetComponent: (widx: Int, cidx: Int, on: Boolean) -> Unit,
    onSetWeaponTint: (widx: Int, tint: Int) -> Unit,
    onFillAmmo: (widx: Int) -> Unit,
    onAddClip: (widx: Int) -> Unit,
) {
    if (armory == null) {
        Note(stringResource(R.string.ammu_loading))
        return
    }

    Section(
        key = SECTION_ATTACHMENTS,
        title = stringResource(R.string.ammu_section_attachments),
        accent = accent,
        collapsed = SECTION_ATTACHMENTS in collapsedSections,
        onToggle = { onToggleSection(SECTION_ATTACHMENTS) },
    ) {
        if (armory.groups.isEmpty()) {
            Note(stringResource(R.string.ammu_no_components))
        } else {
            for ((i, group) in armory.groups.withIndex()) {
                val header = groupHeader(group, i)
                val cur = currentOption(group)
                val stockLabel = stringResource(R.string.ammu_stock)
                val value = cur?.name?.ifBlank { null }
                    ?: if (group.curCidx < 0) stockLabel else "Option ${group.curCidx + 1}"
                val pending = pendingKey?.startsWith("set_component:") == true
                val selectable = selectableCount(group) > 0
                ArrowRow(
                    label = header,
                    value = value,
                    accent = accent,
                    pending = pending,
                    enabled = selectable,
                    onPrev = {
                        cycleBack(group)?.let {
                            onSetComponent(armory.widx, it.cidx, it.on)
                        }
                    },
                    onNext = {
                        cycleForward(group)?.let {
                            onSetComponent(armory.widx, it.cidx, it.on)
                        }
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    if (armory.tints > 0) {
        Section(
            key = SECTION_TINT,
            title = stringResource(R.string.ammu_section_tint),
            accent = accent,
            collapsed = SECTION_TINT in collapsedSections,
            onToggle = { onToggleSection(SECTION_TINT) },
        ) {
            TintChips(
                accent = accent,
                count = armory.tints,
                selected = armory.tint,
                pending = pendingKey?.startsWith("set_weapon_tint:") == true,
                onPick = { onSetWeaponTint(armory.widx, it) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (armory.maxAmmo > 0) {
        Section(
            key = SECTION_AMMO,
            title = stringResource(R.string.ammu_section_ammo),
            accent = accent,
            collapsed = SECTION_AMMO in collapsedSections,
            onToggle = { onToggleSection(SECTION_AMMO) },
        ) {
            AmmoRow(
                accent = accent,
                current = armory.ammo,
                max = armory.maxAmmo,
                clip = armory.clip,
                pendingKey = pendingKey,
                onFill = { onFillAmmo(armory.widx) },
                onAddClip = { onAddClip(armory.widx) },
            )
        }
    }
}

@Composable
private fun Section(
    key: String,
    title: String,
    accent: Color,
    collapsed: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight
                          else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
    if (!collapsed) content()
}

@Composable
private fun ArrowRow(
    label: String,
    value: String,
    accent: Color,
    pending: Boolean,
    enabled: Boolean,
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
                fontSize = 11.sp,
            )
            Text(
                value,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        ArrowIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            accent = accent,
            pending = pending || !enabled,
            onClick = onPrev,
        )
        Spacer(Modifier.size(4.dp))
        ArrowIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            accent = accent,
            pending = pending || !enabled,
            onClick = onNext,
        )
    }
}

@Composable
private fun ArrowIconButton(
    icon: ImageVector,
    accent: Color,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (pending) 0.4f else 1f
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, accent.copy(alpha = alpha), RoundedCornerShape(50))
            .clickable(enabled = !pending, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TintChips(
    accent: Color,
    count: Int,
    selected: Int,
    pending: Boolean,
    onPick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until count) {
            val isSel = i == selected
            val alpha = if (pending) 0.6f else 1f
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) accent.copy(alpha = alpha) else Color.Transparent)
                    .border(
                        1.dp,
                        accent.copy(alpha = alpha * if (isSel) 1f else 0.5f),
                        RoundedCornerShape(6.dp),
                    )
                    .clickable(enabled = !pending) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.ammu_tint_n, i + 1),
                    color = if (isSel) Color.Black else Color(0xFFEDEDED),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun AmmoRow(
    accent: Color,
    current: Int,
    max: Int,
    clip: Int,
    pendingKey: String?,
    onFill: () -> Unit,
    onAddClip: () -> Unit,
) {
    val fillPending = pendingKey == "set_ammo:fill"
    val addPending = pendingKey == "set_ammo:add_clip"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.ammu_ammo_counts, current, max),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        if (clip > 0) {
            Text(
                stringResource(R.string.ammu_clip_size, clip),
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AmmoButton(
                label = stringResource(R.string.ammu_fill),
                accent = accent,
                pending = fillPending,
                onClick = onFill,
            )
            if (clip > 0) {
                AmmoButton(
                    label = stringResource(R.string.ammu_add_clip),
                    accent = accent,
                    pending = addPending,
                    onClick = onAddClip,
                )
            }
        }
    }
}

@Composable
private fun AmmoButton(
    label: String,
    accent: Color,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (pending) 0.4f else 1f
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, accent.copy(alpha = alpha), RoundedCornerShape(6.dp))
            .clickable(enabled = !pending, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = accent.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Color(0xFFCFCFCF),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier.padding(8.dp),
    )
}

/**
 * One accordion section in the weapon rail. [key] is a stable id used
 * by the collapsed-sections saveable set so a rotation does not snap
 * expanded sections closed. [title] is lower-case to match the wheel
 * slot labels ("pistols", "SMGs", "rifles", ...).
 */
private data class RailSection(
    val key: String,
    val title: String,
    val weapons: List<GameState.VWeapon>,
)

/** Section order in the rail — matches the weapon wheel's slot
 *  ordering (pistols at three o'clock, melee at nine, etc.). */
private val RAIL_ORDER = listOf(
    "pistols", "smgs", "rifles", "snipers",
    "shotguns", "heavy", "melee", "thrown",
)

/**
 * Group the owned weapons into rail sections, using the same slot
 * table the weapon wheel uses so the rail matches whichever slot a
 * weapon lives in on the ◯. Weapons the wheel maps nowhere fall into
 * a trailing "misc" group.
 */
private fun railGroupsFor(weapons: List<GameState.VWeapon>): List<RailSection> {
    val bySlot = linkedMapOf<String, MutableList<GameState.VWeapon>>()
    for (w in weapons) {
        val slot = vWheelSlotOf(w.name, w.group)
        val key = slot?.name?.lowercase() ?: "misc"
        bySlot.getOrPut(key) { mutableListOf() }.add(w)
    }
    // Order by RAIL_ORDER, then anything new at the end.
    val ordered = linkedMapOf<String, MutableList<GameState.VWeapon>>()
    for (k in RAIL_ORDER) bySlot[k]?.let { ordered[k] = it }
    for ((k, v) in bySlot) if (k !in ordered) ordered[k] = v
    return ordered.map { (key, list) ->
        RailSection(
            key = key,
            title = key.replaceFirstChar { it.titlecase() },
            weapons = list,
        )
    }
}

/** "weapon_pistol_mk2" → "pistol mk2" for the rail chip. The game's
 *  localised label from the armory frame is used for the title of the
 *  details pane; the rail stays on the stable id so weapons without
 *  a shop entry still read sensibly. */
private fun displayNameOf(w: GameState.VWeapon): String {
    val raw = w.name.ifBlank { "0x%08X".format(w.hash) }
    return raw.removePrefix("weapon_").replace('_', ' ')
}

private const val SECTION_ATTACHMENTS = "attachments"
private const val SECTION_TINT = "tint"
private const val SECTION_AMMO = "ammo"
private val RAIL_WIDTH = 140.dp

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
