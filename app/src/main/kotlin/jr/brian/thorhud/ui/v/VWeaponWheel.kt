package jr.brian.thorhud.ui.v

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.WeaponPick
import jr.brian.thorhud.ui.components.HudPalette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// --- Weapon wheel: drawing ----------------------------------------------
//
// A full-screen overlay shaped like GTA V's weapon wheel: eight slots
// around a hub. What goes in which slot, what a tap means and which slot
// a touch lands in are all decided in VWeaponWheelModel.kt; this file
// only draws that and forwards taps.
//
// [WeaponWheel] is the wheel itself and knows nothing about either game.
// [VWeaponWheelOverlay] feeds it GTA V's loadout; GTA IV's wrapper is
// components/IvWeaponWheel.kt. (The shared wheel lives in this GTA V
// package only because GTA V got it first.)
//
// There are no weapon icons: the game's are Rockstar's artwork and this
// project ships none of it (LEGAL.md), so slots are labelled in text.

private val SegmentIdle = Color(0xFF1F2A24)    // same as the radio picker's buttons
private val SegmentEmpty = Color(0xFF121814)
private val SegmentDropTarget = Color(0xFF2F4A3A)  // dragged slot hovers here
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
 * The GTA V weapon wheel.
 *
 * @param weapons the plugin's owned-weapon list
 * @param equippedHash `weapon_hash` from the live state frame
 * @param pick the weapon last tapped here, if any (see [WeaponPick])
 * @param memory from [rememberVWheelMemory]
 * @param held true while the player holds the game's weapon-wheel
 *             button. Picks are then equipped on release.
 * @param onPick called with the hash of the weapon to equip
 * @param onDismiss see [WeaponWheel]
 */
@Composable
internal fun VWeaponWheelOverlay(
    weapons: List<GameState.VWeapon>,
    equippedHash: Long?,
    pick: WeaponPick?,
    memory: Map<VWheelSlot, Long>,
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
    // game catches up (immediately, or on release while held).
    val selectedHash = pick?.takeIf { it.error == null }?.hash ?: equippedHash
    val segments = vBuildWheel(weapons.map { it.toWheel() }, selectedHash, memory, order)
    WeaponWheel(
        segments = segments,
        // A weapon the plugin's table does not know is on no slot;
        // still name what is in hand.
        selectedLabel = vWeaponLabel(
            segments.firstOrNull { it.isSelected }?.shown?.name, selectedHash),
        labelOf = { vWeaponLabel(it.name, it.hash) },
        error = pick?.error,
        // GTA V has its own wheel open while the button is held, so a
        // pick made then only lands when the button is released.
        hint = stringResource(
            if (held) R.string.v_wheel_hint_held else R.string.v_wheel_hint_open,
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

/**
 * The wheel, for either game.
 *
 * @param segments eight slots from the game's model (`vBuildWheel`,
 *                 `ivBuildWheel`), indexed by [VWheelSlot.ordinal]
 * @param selectedLabel name of the selected weapon, for the hub
 * @param labelOf display name for a weapon on a slot
 * @param error why the last pick was refused, if it was
 * @param hint one line telling the player how to pick and how to leave
 * @param held true while the player holds the wheel button on the
 *             controller. The wheel is then closed by releasing the
 *             button, not by a tap.
 * @param onPick called with the `hash` of the weapon to equip
 * @param onDismiss called on a tap on the hub or outside the wheel,
 *                  unless [held]
 */
@Composable
internal fun WeaponWheel(
    segments: List<VWheelSegment>,
    selectedLabel: String,
    labelOf: @Composable (VWheelWeapon) -> String,
    error: String?,
    hint: String,
    held: Boolean,
    palette: HudPalette,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSegments by rememberUpdatedState(segments)
    val currentHeld by rememberUpdatedState(held)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnResetOrder by rememberUpdatedState(onResetOrder)

    // Drag state. [dragSource] is the angular position being picked up
    // (null when idle); [dragPos] is the finger's local position in px;
    // [dragHover] is the slot it is over, if any. All three are set and
    // cleared together.
    var dragSource by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var dragHover by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            // One tap detector for the whole overlay: slots are wedges,
            // not rectangles, so hit-testing is by angle and radius.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tap ->
                    if (dragSource != null) return@detectTapGestures
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
            // Long-press on a slot picks it up; long-press on the hub
            // resets the saved layout. Drag happens with the same
            // pointer so a quick tap still falls through to the tap
            // detector above.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outer = minOf(size.width, size.height) / 2f *
                            V_WHEEL_OUTER_FRACTION
                        val index = vWheelSegmentAt(pos.x - cx, pos.y - cy, outer)
                        if (index != null) {
                            dragSource = index
                            dragPos = pos
                            dragHover = index
                        } else if (hypot(pos.x - cx, pos.y - cy) <
                                   outer * V_WHEEL_INNER_FRACTION) {
                            currentOnResetOrder()
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragSource == null) return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragPos = change.position
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outer = minOf(size.width, size.height) / 2f *
                            V_WHEEL_OUTER_FRACTION
                        dragHover = vWheelSegmentAt(
                            change.position.x - cx,
                            change.position.y - cy,
                            outer,
                        )
                    },
                    onDragEnd = {
                        val src = dragSource
                        val dst = dragHover
                        if (src != null && dst != null && src != dst) {
                            currentOnReorder(src, dst)
                        }
                        dragSource = null
                        dragPos = null
                        dragHover = null
                    },
                    onDragCancel = {
                        dragSource = null
                        dragPos = null
                        dragHover = null
                    },
                )
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
        val density = LocalDensity.current
        val boxCxPx = with(density) { maxWidth.toPx() } / 2f
        val boxCyPx = with(density) { maxHeight.toPx() } / 2f

        Canvas(Modifier.fillMaxSize()) {
            val outer = minOf(size.width, size.height) / 2f * V_WHEEL_OUTER_FRACTION
            val inner = outer * V_WHEEL_INNER_FRACTION
            val mid = (outer + inner) / 2f
            segments.forEachIndexed { index, segment ->
                // Each slot is a thick stroke along the wheel's mid
                // radius. Angles are clockwise from three o'clock, the
                // convention vWheelSegmentAt uses.
                val hovered = index == dragHover && dragSource != null &&
                              dragHover != dragSource
                drawArc(
                    color = when {
                        hovered                   -> SegmentDropTarget
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
            // The dragged slot's home label is drawn as a dim
            // placeholder; the actual label follows the finger below.
            val isDragging = index == dragSource
            val angle = Math.toRadians((index * V_WHEEL_SWEEP_DEG).toDouble())
            VWheelSlotLabel(
                segment = segment,
                labelOf = labelOf,
                ghostOnly = isDragging,
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

        // Dragged slot's label follows the finger.
        val src = dragSource
        val pos = dragPos
        if (src != null && pos != null) {
            VWheelSlotLabel(
                segment = segments[src],
                labelOf = labelOf,
                ghostOnly = false,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (pos.x - boxCxPx).roundToInt(),
                            (pos.y - boxCyPx).roundToInt(),
                        )
                    }
                    .width(outerRadius * 0.5f),
            )
        }

        VWheelHub(
            selected = segments.firstOrNull { it.isSelected },
            selectedLabel = selectedLabel,
            error = error,
            hint = hint,
            modifier = Modifier.width(innerRadius * 1.7f),
        )
    }
}

/** What a slot holds: the weapon it would equip, and where it is among
 *  the slot's weapons when there are several to step through.
 *
 *  [ghostOnly] renders the label as a dim placeholder, used at the drag
 *  source while the player is dragging it elsewhere. */
@Composable
private fun VWheelSlotLabel(
    segment: VWheelSegment,
    labelOf: @Composable (VWheelWeapon) -> String,
    modifier: Modifier,
    ghostOnly: Boolean = false,
) {
    val weapon = segment.shown
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (weapon != null) labelOf(weapon) else "—",
            color = when {
                ghostOnly          -> TextDim
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
            color = when {
                ghostOnly          -> TextDim
                segment.isSelected -> TextOnAccent
                else               -> TextDim
            },
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
    selectedLabel: String,
    error: String?,
    hint: String,
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
            selectedLabel,
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
            hint,
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}
