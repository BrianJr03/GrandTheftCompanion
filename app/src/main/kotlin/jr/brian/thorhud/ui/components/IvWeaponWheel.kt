package jr.brian.thorhud.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.WeaponPick
import jr.brian.thorhud.ui.v.VWheelSlot
import jr.brian.thorhud.ui.v.WeaponWheel

/**
 * The weapon wheel for GTA IV: the wheel drawn for GTA V
 * (ui/v/VWeaponWheel.kt), filled from GTA IV's `owned` list by
 * [ivBuildWheel] and labelled from the GTA IV weapon-name table.
 *
 * GTA IV has no wheel of its own, so unlike GTA V nothing is deferred:
 * a tapped weapon is equipped at once, whether the wheel was opened by
 * holding LB / L1 or by tapping the weapon card.
 *
 * @param owned `owned` from the live state frame
 * @param equippedId `weapon` from the live state frame
 * @param pick the weapon last tapped here, if any (see [WeaponPick])
 * @param held true while the player holds LB / L1
 * @param onPick called with the id of the weapon to equip, as a Long
 *               because that is what the command channel carries
 * @param onDismiss see [WeaponWheel]
 */
@Composable
internal fun IvWeaponWheelOverlay(
    owned: List<GameState.OwnedWeapon>,
    equippedId: Int?,
    pick: WeaponPick?,
    held: Boolean,
    palette: HudPalette,
    order: List<VWheelSlot>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A pick that was not refused counts as selected right away; the
    // game confirms it a frame or two later.
    val selectedId = pick?.takeIf { it.error == null }?.hash?.toInt() ?: equippedId
    WeaponWheel(
        segments = ivBuildWheel(owned, selectedId, order),
        selectedLabel = weaponName(selectedId),
        labelOf = { weaponName(it.hash.toInt()) },
        error = pick?.error,
        hint = stringResource(
            if (held) R.string.v_wheel_hint_held_iv else R.string.v_wheel_hint_open,
        ),
        held = held,
        palette = palette,
        onPick = onPick,
        onDismiss = onDismiss,
        onReorder = onReorder,
        onResetOrder = onResetOrder,
        modifier = modifier,
    )
}
