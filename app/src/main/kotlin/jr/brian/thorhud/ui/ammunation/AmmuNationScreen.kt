package jr.brian.thorhud.ui.ammunation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Ammu-Nation sheet (GTA V only). Opens on a long-press of the
 * on-foot weapon card. Shows a horizontally-scrolling strip of every
 * owned weapon, then — for whichever is picked — Attachments / Tint
 * / Ammo sections built off the plugin's `armory` frame.
 *
 * Compose unconditionally with [visible] so the slide-out animation
 * plays on close. Opens by requesting the equipped weapon's page;
 * tapping a weapon in the strip asks the plugin for that one instead
 * and the sheet follows the server's `ver` bump.
 */
@Composable
internal fun AmmuNationScreen(
    visible: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    viewModel: AmmuNationViewModel = hiltViewModel(),
) {
    val armory by viewModel.armory.collectAsStateWithLifecycle()
    val weapons by viewModel.weapons.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val onFoot = state?.inVehicle == false

    // 3-second grace: a stall that briefly drops on-foot should not
    // slam the sheet shut. Also hop out if the plugin says in-vehicle
    // for longer than that: the plugin refuses our commands anyway.
    LaunchedEffect(visible, state?.inVehicle) {
        if (visible && state?.inVehicle == true) {
            delay(3000.milliseconds)
            onDismiss()
        }
    }

    // On open, ask for the currently-equipped weapon. If the player
    // is unarmed (hash 0 or the kUnarmed table slot) nothing opens;
    // the sheet shows "equip a weapon".
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val equipped = state?.weaponHash
        val fallback = weapons.firstOrNull()?.hash
        val target = equipped?.takeIf { it != 0L && weapons.any { w -> w.hash == it } }
            ?: fallback
        if (target != null) viewModel.open(target)
    }

    val collapsed = rememberSaveable(saver = SetSaver) {
        mutableStateOf(emptySet<String>())
    }
    val toggleSection: (String) -> Unit = { s ->
        collapsed.value =
            if (s in collapsed.value) collapsed.value - s else collapsed.value + s
    }

    val title = armory?.name?.ifBlank { null }
        ?: stringResource(R.string.ammu_title_fallback)

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
                    !onFoot -> Note(stringResource(R.string.ammu_get_on_foot))
                    weapons.isEmpty() -> Note(stringResource(R.string.ammu_get_armed))
                    else -> AmmuBody(
                        accent = accent,
                        armory = armory,
                        weapons = weapons,
                        pendingKey = result?.takeIf { it.error == null }?.pendingKey,
                        collapsed = collapsed.value,
                        onToggleSection = toggleSection,
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
                    )
                }
            }
        }
    }
}

@Composable
private fun AmmuBody(
    accent: Color,
    armory: Armory?,
    weapons: List<GameState.VWeapon>,
    pendingKey: String?,
    collapsed: Set<String>,
    onToggleSection: (String) -> Unit,
    onSelectWeapon: (Long) -> Unit,
    onSetComponent: (widx: Int, cidx: Int, on: Boolean) -> Unit,
    onSetWeaponTint: (widx: Int, tint: Int) -> Unit,
    onFillAmmo: (widx: Int) -> Unit,
    onAddClip: (widx: Int) -> Unit,
) {
    WeaponStrip(
        accent = accent,
        weapons = weapons,
        selectedHash = armory?.weaponHash ?: 0L,
        onSelect = onSelectWeapon,
    )
    Spacer(Modifier.height(8.dp))

    if (armory == null) {
        Note(stringResource(R.string.ammu_loading))
        return
    }

    // Attachments.
    Section(
        key = SECTION_ATTACHMENTS,
        title = stringResource(R.string.ammu_section_attachments),
        accent = accent,
        collapsed = SECTION_ATTACHMENTS in collapsed,
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

    // Tint and Ammo only work for weapons the DLC catalogue knows.
    if (!armory.inShop) {
        Note(stringResource(R.string.ammu_not_in_shop))
        return
    }

    // Tint.
    if (armory.tints > 0) {
        Section(
            key = SECTION_TINT,
            title = stringResource(R.string.ammu_section_tint),
            accent = accent,
            collapsed = SECTION_TINT in collapsed,
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

    // Ammo.
    if (armory.maxAmmo > 0) {
        Section(
            key = SECTION_AMMO,
            title = stringResource(R.string.ammu_section_ammo),
            accent = accent,
            collapsed = SECTION_AMMO in collapsed,
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
private fun WeaponStrip(
    accent: Color,
    weapons: List<GameState.VWeapon>,
    selectedHash: Long,
    onSelect: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (w in weapons) {
            WeaponChip(
                label = w.name.ifBlank { "0x%08X".format(w.hash) },
                selected = w.hash == selectedHash,
                accent = accent,
                onClick = { onSelect(w.hash) },
            )
        }
    }
}

@Composable
private fun WeaponChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val fg = if (selected) Color.Black else Color(0xFFEDEDED)
    val bg = if (selected) accent else Color.Transparent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, accent.copy(alpha = if (selected) 1f else 0.5f),
                    RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.removePrefix("weapon_"),
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
        )
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
            imageVector = if (collapsed) Icons.Filled.KeyboardArrowRight
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
                    .size(width = 56.dp, height = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) accent.copy(alpha = alpha) else Color.Transparent)
                    .border(1.dp, accent.copy(alpha = alpha * if (isSel) 1f else 0.5f),
                            RoundedCornerShape(6.dp))
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
    val addPending  = pendingKey == "set_ammo:add_clip"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.ammu_ammo_counts, current, max),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
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
            fontSize = 13.sp,
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
    )
}

private const val SECTION_ATTACHMENTS = "attachments"
private const val SECTION_TINT = "tint"
private const val SECTION_AMMO = "ammo"

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
