package jr.brian.thorhud.ui.ammunation

import jr.brian.thorhud.model.ArmoryGroup
import jr.brian.thorhud.model.GameState

/**
 * Pure logic for the Ammu-Nation sheet. No Android imports: lives in
 * its own file so the cycling / packing / naming can be unit-tested.
 *
 * The wire format is already settled by [GameStateRepository]: this
 * file is just the client-side ◀ ▶ picker + the fallback-group-name
 * table.
 */

/** Fill action for the ammo row. 0 = fill to GET_MAX_AMMO; 1 = +1
 *  clip (clamped at the cap). */
enum class AmmoAction(val wire: Int) { Fill(0), AddClip(1) }

/**
 * The command the UI should fire to move group [group] forward one
 * step through its ◀ ▶ cycle. The cycle is:
 *
 *   stock → option-1 → option-2 → ... → option-N → stock → ...
 *
 * where "stock" means "only the default is fitted". `null` means the
 * group has nothing selectable (e.g. only a WCT_CLIP1 and no
 * alternatives) and the UI disables the arrows.
 *
 * Returns a [CycleCommand] with the (cidx, on) pair to send, not the
 * packed wire form — the ViewModel adds the widx.
 */
data class CycleCommand(val cidx: Int, val on: Boolean)

fun cycleForward(group: ArmoryGroup): CycleCommand? =
    cycle(group, forward = true)

fun cycleBack(group: ArmoryGroup): CycleCommand? =
    cycle(group, forward = false)

private fun cycle(group: ArmoryGroup, forward: Boolean): CycleCommand? {
    val options = group.options
    val choices = options.filter { !it.isDefault }
    if (choices.isEmpty()) return null

    val cur = group.curCidx
    val currentIdxInChoices =
        if (cur < 0) -1 else choices.indexOfFirst { it.cidx == cur }

    val nextIdx = if (forward) {
        if (currentIdxInChoices == choices.lastIndex) -1   // wrap to stock
        else currentIdxInChoices + 1
    } else {
        if (currentIdxInChoices == -1) choices.lastIndex   // from stock → last
        else if (currentIdxInChoices == 0) -1              // from first → stock
        else currentIdxInChoices - 1
    }

    return if (nextIdx == -1) {
        // Target state = stock. If something non-default is fitted,
        // we remove it; otherwise nothing to send (shouldn't happen
        // because the row would be a no-op in that case).
        if (cur < 0) null else CycleCommand(cidx = cur, on = false)
    } else {
        CycleCommand(cidx = choices[nextIdx].cidx, on = true)
    }
}

/** Human label the sheet uses for a group whose bone id maps to a
 *  known category. Unknown bones fall through to "Attachment N" via
 *  [groupHeader]. English only in v1; locale copies land once the
 *  catalogue stabilises on-device. */
val kBoneSlotNames: Map<Long, String> = mapOf(
    // Seeded in Gate A0 (pistol family + Special Carbine + Mk II).
    3723347892L to "Clip",
    679107254L  to "Flashlight",
    945598191L  to "Muzzle",
    1863181664L to "Muzzle",
    1684637069L to "Scope",
    196630833L  to "Scope",
    2972950469L to "Grip",
    962500902L  to "Finish",
    // Added after the Gate A1 sweep on Franklin's loadout: the same
    // attachment category uses different bone ids on different DLC
    // weapon families (Combat PDW, Bullpup Rifle, Heavy Shotgun,
    // Marksman Rifle).
    291640902L  to "Clip",         // Combat PDW clips (Default / Extended / Drum)
    2722126698L to "Flashlight",   // Bullpup Rifle flashlight
    3748215485L to "Grip",         // Combat PDW / Heavy Shotgun / Marksman Rifle
)

/**
 * Header for one attachment group. [index] is the group's position
 * in the catalogue so unknown bones get a stable "Attachment 1 /
 * 2 / ..." fallback instead of their raw hash.
 */
fun groupHeader(group: ArmoryGroup, index: Int): String =
    kBoneSlotNames[group.bone] ?: "Attachment ${index + 1}"

/**
 * Which option the group's row should display as "current". Returns
 * null when only the stock default is present (the row says "stock");
 * returns the fitted option otherwise.
 */
fun currentOption(group: ArmoryGroup): GameState.ArmoryOption? =
    group.options.firstOrNull { it.cidx == group.curCidx }

/** How many non-default options the group has — the UI greys the
 *  arrows when this is zero. */
fun selectableCount(group: ArmoryGroup): Int =
    group.options.count { !it.isDefault }

/**
 * Clamp an +1-clip action's ammo delta the same way the plugin does.
 * Here for parity in a shared test rather than for correctness on the
 * wire (the plugin re-clamps server-side), but makes the ammo row's
 * displayed "after" value stay honest when the user holds the button.
 */
fun clampedAdd(current: Int, clipSize: Int, maxAmmo: Int): Int {
    val add = clipSize.coerceAtLeast(0)
    val headroom = (maxAmmo - current).coerceAtLeast(0)
    return current + add.coerceAtMost(headroom)
}
