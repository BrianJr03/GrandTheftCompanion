package jr.brian.thorhud.ui.v

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.WeaponPick
import jr.brian.thorhud.ui.components.HudPalette
import kotlin.math.cos
import kotlin.math.sin

// --- GTA V weapon wheel: drawing ----------------------------------------
//
// A full-screen overlay shaped like the game's weapon wheel: eight slots
// around a hub. What goes in which slot, what a tap means and which slot
// a touch lands in are all decided in VWeaponWheelModel.kt; this file
// only draws that and forwards taps.
//
// There are no weapon icons: the game's are Rockstar's artwork and this
// project ships none of it (LEGAL.md), so slots are labelled in text.

private val SegmentIdle = Color(0xFF1F2A24)    // same as the radio picker's buttons
private val SegmentEmpty = Color(0xFF121814)
private val TextBright = Color(0xFFEDEDED)
private val TextDim = Color(0xFF6A7A6E)
private val TextOnAccent = Color(0xFF000000)
private val TextError = Color(0xFFE06060)

/** Gap between neighbouring slots, in degrees of arc. */
private const val GAP_DEG = 2f

private fun GameState.VWeapon.toWheel() = VWheelWeapon(hash, name, group, ammo)

/**
 * Which weapon each slot was last seen holding, so that a slot offers
 * the weapon the player last used from it rather than always its first.
 * It follows the equipped weapon however it got equipped (this wheel,
 * the game's own wheel, a pickup), so call it wherever the GTA V HUD is
 * composed, not only while the wheel is open.
 *
 * Kept for the life of the composition, not persisted, and not per
 * character: a remembered weapon the current character does not own is
 * simply ignored by [vBuildWheel].
 */
@Composable
internal fun rememberVWheelMemory(
    weapons: List<GameState.VWeapon>,
    equippedHash: Long?,
): Map<VWheelSlot, Long> {
    val memory = remember { mutableStateMapOf<VWheelSlot, Long>() }
    LaunchedEffect(weapons, equippedHash) {
        val hash = equippedHash ?: return@LaunchedEffect
        val slot = vWheelSlotOfHash(weapons.map { it.toWheel() }, hash)
            ?: return@LaunchedEffect
        memory[slot] = hash
    }
    return memory
}

/**
 * The weapon wheel.
 *
 * @param weapons the plugin's owned-weapon list
 * @param equippedHash `weapon_hash` from the live state frame
 * @param pick the weapon last tapped here, if any (see [WeaponPick])
 * @param memory from [rememberVWheelMemory]
 * @param held true while the player holds the game's weapon-wheel
 *             button. The wheel is then closed by releasing the button,
 *             not by a tap, and picks are equipped on release.
 * @param onPick called with the hash of the weapon to equip
 * @param onDismiss called on a tap on the hub or outside the wheel,
 *                  unless [held]
 */
@Composable
internal fun VWeaponWheelOverlay(
    weapons: List<GameState.VWeapon>,
    equippedHash: Long?,
    pick: WeaponPick?,
    memory: Map<VWheelSlot, Long>,
    held: Boolean,
    palette: HudPalette,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A pick that was not refused counts as selected right away; the
    // game catches up (immediately, or on release while held).
    val selectedHash = pick?.takeIf { it.error == null }?.hash ?: equippedHash
    val segments = vBuildWheel(weapons.map { it.toWheel() }, selectedHash, memory)

    val currentSegments by rememberUpdatedState(segments)
    val currentHeld by rememberUpdatedState(held)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            // One tap detector for the whole overlay: slots are wedges,
            // not rectangles, so hit-testing is by angle and radius.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tap ->
                    val outer = minOf(size.width, size.height) / 2f *
                        V_WHEEL_OUTER_FRACTION
                    val index = vWheelSegmentAt(
                        dx = tap.x - size.width / 2f,
                        dy = tap.y - size.height / 2f,
                        outerRadius = outer,
                    )
                    if (index == null) {
                        if (!currentHeld) currentOnDismiss()
                    } else {
                        vWheelTapTarget(currentSegments[index])?.let(currentOnPick)
                    }
                })
            }
            // detectTapGestures only consumes a down pointer once a tap
            // resolves, so a horizontal drag on the overlay would still
            // reach HudPager's scrollable underneath and flip the page.
            // Swallow drag deltas here so the pager stays put while the
            // wheel is up.
            .pointerInput(Unit) {
                detectDragGestures(onDrag = { _, _ -> })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Same geometry as the tap detector above and the Canvas below:
        // a wheel centred in the box, sized off its shorter side.
        val outerRadius = minOf(maxWidth, maxHeight) / 2 * V_WHEEL_OUTER_FRACTION
        val innerRadius = outerRadius * V_WHEEL_INNER_FRACTION
        val midRadius = (outerRadius + innerRadius) / 2

        Canvas(Modifier.fillMaxSize()) {
            val outer = minOf(size.width, size.height) / 2f * V_WHEEL_OUTER_FRACTION
            val inner = outer * V_WHEEL_INNER_FRACTION
            val mid = (outer + inner) / 2f
            segments.forEachIndexed { index, segment ->
                // Each slot is a thick stroke along the wheel's mid
                // radius. Angles are clockwise from three o'clock, the
                // convention vWheelSegmentAt uses.
                drawArc(
                    color = when {
                        segment.isSelected        -> palette.accent
                        segment.weapons.isEmpty() -> SegmentEmpty
                        else                      -> SegmentIdle
                    },
                    startAngle = index * V_WHEEL_SWEEP_DEG -
                        V_WHEEL_SWEEP_DEG / 2f + GAP_DEG / 2f,
                    sweepAngle = V_WHEEL_SWEEP_DEG - GAP_DEG,
                    useCenter = false,
                    topLeft = Offset(center.x - mid, center.y - mid),
                    size = Size(mid * 2f, mid * 2f),
                    style = Stroke(width = outer - inner),
                )
            }
        }

        segments.forEachIndexed { index, segment ->
            val angle = Math.toRadians((index * V_WHEEL_SWEEP_DEG).toDouble())
            VWheelSlotLabel(
                segment = segment,
                modifier = Modifier
                    .offset(
                        x = midRadius * cos(angle).toFloat(),
                        y = midRadius * sin(angle).toFloat(),
                    )
                    // About the chord of a slot at mid radius, so two
                    // lines of text stay inside the wedge.
                    .width(outerRadius * 0.5f),
            )
        }

        VWheelHub(
            selected = segments.firstOrNull { it.isSelected },
            selectedHash = selectedHash,
            error = pick?.error,
            held = held,
            modifier = Modifier.width(innerRadius * 1.7f),
        )
    }
}

/** What a slot holds: the weapon it would equip, and where it is among
 *  the slot's weapons when there are several to step through. */
@Composable
private fun VWheelSlotLabel(segment: VWheelSegment, modifier: Modifier) {
    val weapon = segment.shown
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            weapon?.let { vWeaponLabel(it.name, it.hash) } ?: "—",
            color = when {
                segment.isSelected -> TextOnAccent
                weapon == null     -> TextDim
                else               -> TextBright
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            if (segment.weapons.size > 1) {
                "${segment.slot.label} ${segment.shownIndex + 1}/${segment.weapons.size}"
            } else {
                segment.slot.label
            },
            color = if (segment.isSelected) TextOnAccent else TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** The middle of the wheel: the selected weapon in full, its ammo, why
 *  the last pick failed if it did, and how to get out. */
@Composable
private fun VWheelHub(
    selected: VWheelSegment?,
    selectedHash: Long?,
    error: String?,
    held: Boolean,
    modifier: Modifier,
) {
    val weapon = selected?.shown
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (selected != null) {
            Text(
                selected.slot.label,
                color = TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Text(
            // A weapon the plugin's table does not know is on no slot;
            // still name what is in hand.
            vWeaponLabel(weapon?.name, selectedHash),
            color = TextBright,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (weapon != null && weapon.ammo > 0) {
            Text(
                "${stringResource(R.string.v_label_ammo)} ${weapon.ammo}",
                color = TextBright,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (error != null) {
            Text(
                stringResource(R.string.v_wheel_failed, error),
                color = TextError,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(
                if (held) R.string.v_wheel_hint_held else R.string.v_wheel_hint_open,
            ),
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}
