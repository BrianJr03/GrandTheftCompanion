package jr.brian.thorhud.ui.cheats

import androidx.annotation.StringRes
import jr.brian.thorhud.R

/**
 * The companion's cheats catalog, shared between GTA IV and GTA V. One
 * [CheatDef] per cheat id; the catalog maps that id to a label and a
 * [games] mask describing which plugins honour it. The plugin side is
 * authoritative: a tap sends only the id and the plugin decides what
 * to run; this file is only how the UI picks which buttons to show.
 *
 * No Android UI imports here: the catalog is unit-tested in
 * app/src/test/.../CheatCatalogTest.kt.
 *
 * Keep the ids and the (one-shot vs toggle) split in sync with
 * asi/src/cheats.h and (eventually) asi-v/src/cheats.h.
 */
internal data class CheatDef(
    val id: Int,
    val isToggle: Boolean,
    val games: Int,
    @StringRes val labelRes: Int,
)

internal const val CHEAT_IV: Int = 0b01
internal const val CHEAT_V: Int = 0b10
internal const val CHEAT_BOTH: Int = CHEAT_IV or CHEAT_V

// First toggle id. Toggle id N lights bit (N - FIRST_TOGGLE_ID) in the
// mask the state frame carries. Must match asi/src/cheats.h's
// kFirstToggle.
internal const val FIRST_TOGGLE_ID = 20

internal val CHEATS: List<CheatDef> = listOf(
    // One-shots (ids 1..19).
    CheatDef(1,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_1_heal),
    CheatDef(2,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_2_wanted_up),
    CheatDef(3,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_3_wanted_clear),
    CheatDef(4,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_4_all_weapons),
    CheatDef(5,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_5_max_ammo),
    CheatDef(6,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_6_fix_vehicle),
    CheatDef(7,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_7_next_weather),
    CheatDef(8,  isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_8_next_time),
    CheatDef(9,  isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_9_fill_special),
    // Spawn-car buttons: V-only in v1. One id per model (10..15) so
    // the plugin's state machine owns the model identity, not the arg
    // payload. Labels are the model names.
    CheatDef(10, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_10_spawn_adder),
    CheatDef(11, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_11_spawn_zentorno),
    CheatDef(12, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_12_spawn_bati),
    CheatDef(13, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_13_spawn_sanchez),
    CheatDef(14, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_14_spawn_frogger),
    CheatDef(15, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_15_spawn_bmx),
    CheatDef(16, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_16_wanted_down),
    // Parachute is cross-game: V uses GIVE_WEAPON_TO_PED with
    // gadget_parachute; IV uses GIVE_WEAPON_TO_CHAR with
    // WEAPON_PARACHUTE (id 25).
    CheatDef(17, isToggle = false, games = CHEAT_BOTH, labelRes = R.string.cheat_17_give_parachute),
    CheatDef(18, isToggle = false, games = CHEAT_V,    labelRes = R.string.cheat_18_skyfall),
    // IV phone-cheat weapon sets (ids 19, 40, 41). V has its own
    // DNMAGIC "all weapons" (id 4) and does not include these.
    CheatDef(19, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_19_advanced_weapons),

    // Toggles (ids 20..34).
    CheatDef(20, isToggle = true,  games = CHEAT_BOTH, labelRes = R.string.cheat_20_invincible),
    CheatDef(21, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_21_infinite_clip),
    CheatDef(22, isToggle = true,  games = CHEAT_IV,   labelRes = R.string.cheat_22_fast_reload),
    CheatDef(23, isToggle = true,  games = CHEAT_IV,   labelRes = R.string.cheat_23_never_tired),
    CheatDef(24, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_24_fast_run),
    CheatDef(25, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_25_low_gravity),
    CheatDef(26, isToggle = true,  games = CHEAT_BOTH, labelRes = R.string.cheat_26_slow_motion),
    CheatDef(27, isToggle = true,  games = CHEAT_BOTH, labelRes = R.string.cheat_27_police_ignore),
    CheatDef(28, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_28_drunk),
    CheatDef(29, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_29_fast_swim),
    CheatDef(30, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_30_super_jump),
    CheatDef(31, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_31_explosive_ammo),
    CheatDef(32, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_32_explosive_melee),
    CheatDef(33, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_33_incendiary_ammo),
    CheatDef(34, isToggle = true,  games = CHEAT_V,    labelRes = R.string.cheat_34_slippery),
    // IV phone-cheat weapon sets, continued.
    CheatDef(40, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_40_poor_weapons),
    CheatDef(41, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_41_ultimate),
    // Base-IV phone-cheat vehicle spawns (ids 50..58). Hashes come
    // from GET_HASH_KEY on the game's model-info-store names, so a
    // wrong name just makes that one spawn time out.
    CheatDef(50, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_50_spawn_fib_buffalo),
    CheatDef(51, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_51_spawn_cognoscenti),
    CheatDef(52, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_52_spawn_comet),
    CheatDef(53, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_53_spawn_jetmax),
    CheatDef(54, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_54_spawn_annihilator),
    CheatDef(55, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_55_spawn_super_gt),
    CheatDef(56, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_56_spawn_nrg900),
    CheatDef(57, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_57_spawn_sanchez),
    CheatDef(58, isToggle = false, games = CHEAT_IV,   labelRes = R.string.cheat_58_spawn_turismo),
)

/** Cheats the current game supports, in catalog order. */
internal fun visibleCheats(isGtaV: Boolean): List<CheatDef> {
    val wanted = if (isGtaV) CHEAT_V else CHEAT_IV
    return CHEATS.filter { it.games and wanted != 0 }
}

/** True when toggle [id] is lit in [mask] (null mask = never). */
internal fun isOn(mask: Int?, id: Int): Boolean {
    if (mask == null) return false
    if (id < FIRST_TOGGLE_ID) return false
    return (mask ushr (id - FIRST_TOGGLE_ID)) and 1 == 1
}
