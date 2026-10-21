package jr.brian.thorhud.ui.cheats

import androidx.annotation.StringRes
import jr.brian.thorhud.R

/**
 * The companion's cheats catalog, shared between GTA IV and GTA V. One
 * [CheatDef] per cheat id; the catalog maps that id to a label, a
 * [games] mask describing which plugins honour it, and a [category]
 * for grouping on the cheats tab. The plugin side is authoritative:
 * a tap sends only the id and the plugin decides what to run.
 *
 * No Android UI imports here: the catalog is unit-tested in
 * app/src/test/.../CheatCatalogTest.kt.
 *
 * Keep the ids and the (one-shot vs toggle) split in sync with
 * asi/src/cheats.h and asi-v/src/cheats.h.
 */
internal data class CheatDef(
    val id: Int,
    val isToggle: Boolean,
    val games: Int,
    val category: CheatCategory,
    @StringRes val labelRes: Int,
)

/**
 * Visual grouping on the cheats tab. CheatsScreen renders each
 * category as its own collapsible section with a header; order of
 * entries here is the order the headers appear.
 */
internal enum class CheatCategory(@StringRes val labelRes: Int) {
    Health(R.string.cheats_section_health),
    Wanted(R.string.cheats_section_wanted),
    Weapons(R.string.cheats_section_weapons),
    Vehicle(R.string.cheats_section_vehicle),
    World(R.string.cheats_section_world),
    Toggles(R.string.cheats_section_toggles),
}

internal const val CHEAT_IV: Int = 0b01
internal const val CHEAT_V: Int = 0b10
internal const val CHEAT_BOTH: Int = CHEAT_IV or CHEAT_V

// First toggle id. Toggle id N lights bit (N - FIRST_TOGGLE_ID) in the
// mask the state frame carries. Must match asi/src/cheats.h's
// kFirstToggle.
internal const val FIRST_TOGGLE_ID = 20

// Catalog order dictates both section placement and within-section
// placement. CheatsScreen chunks each section's items two-wide, so
// entries that belong visually paired should land at positions 2k /
// 2k+1 inside their category. The wanted trio is grouped so clear
// sits next to +1 and -1, matching the user's "cluster the wanted
// cheats" ask.
internal val CHEATS: List<CheatDef> = listOf(
    // -- Health --------------------------------------------------
    CheatDef(1,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Health,
             labelRes = R.string.cheat_1_heal),
    CheatDef(41, isToggle = false, games = CHEAT_IV,   category = CheatCategory.Health,
             labelRes = R.string.cheat_41_ultimate),

    // -- Wanted (clear right next to +1/-1) ---------------------
    CheatDef(3,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Wanted,
             labelRes = R.string.cheat_3_wanted_clear),
    CheatDef(2,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Wanted,
             labelRes = R.string.cheat_2_wanted_up),
    CheatDef(16, isToggle = false, games = CHEAT_V,    category = CheatCategory.Wanted,
             labelRes = R.string.cheat_16_wanted_down),

    // -- Weapons -------------------------------------------------
    CheatDef(4,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Weapons,
             labelRes = R.string.cheat_4_all_weapons),
    CheatDef(5,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Weapons,
             labelRes = R.string.cheat_5_max_ammo),
    CheatDef(19, isToggle = false, games = CHEAT_IV,   category = CheatCategory.Weapons,
             labelRes = R.string.cheat_19_advanced_weapons),
    CheatDef(40, isToggle = false, games = CHEAT_IV,   category = CheatCategory.Weapons,
             labelRes = R.string.cheat_40_poor_weapons),
    CheatDef(17, isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Weapons,
             labelRes = R.string.cheat_17_give_parachute),

    // -- Vehicle (IV spawn pulled; V-only curated models) --------
    CheatDef(6,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_6_fix_vehicle),
    CheatDef(10, isToggle = false, games = CHEAT_V,    category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_10_spawn_adder),
    CheatDef(11, isToggle = false, games = CHEAT_V,    category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_11_spawn_zentorno),
    CheatDef(12, isToggle = false, games = CHEAT_V,    category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_12_spawn_bati),
    CheatDef(13, isToggle = false, games = CHEAT_V,    category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_13_spawn_sanchez),
    CheatDef(14, isToggle = false, games = CHEAT_V,    category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_14_spawn_frogger),
    CheatDef(15, isToggle = false, games = CHEAT_V,    category = CheatCategory.Vehicle,
             labelRes = R.string.cheat_15_spawn_bmx),

    // -- World ---------------------------------------------------
    CheatDef(7,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.World,
             labelRes = R.string.cheat_7_next_weather),
    CheatDef(8,  isToggle = false, games = CHEAT_BOTH, category = CheatCategory.World,
             labelRes = R.string.cheat_8_next_time),
    CheatDef(9,  isToggle = false, games = CHEAT_V,    category = CheatCategory.World,
             labelRes = R.string.cheat_9_fill_special),
    CheatDef(18, isToggle = false, games = CHEAT_V,    category = CheatCategory.World,
             labelRes = R.string.cheat_18_skyfall),

    // -- Toggles -------------------------------------------------
    CheatDef(20, isToggle = true,  games = CHEAT_BOTH, category = CheatCategory.Toggles,
             labelRes = R.string.cheat_20_invincible),
    CheatDef(21, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_21_infinite_clip),
    CheatDef(22, isToggle = true,  games = CHEAT_IV,   category = CheatCategory.Toggles,
             labelRes = R.string.cheat_22_fast_reload),
    CheatDef(23, isToggle = true,  games = CHEAT_IV,   category = CheatCategory.Toggles,
             labelRes = R.string.cheat_23_never_tired),
    CheatDef(24, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_24_fast_run),
    CheatDef(25, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_25_low_gravity),
    CheatDef(26, isToggle = true,  games = CHEAT_BOTH, category = CheatCategory.Toggles,
             labelRes = R.string.cheat_26_slow_motion),
    CheatDef(27, isToggle = true,  games = CHEAT_BOTH, category = CheatCategory.Toggles,
             labelRes = R.string.cheat_27_police_ignore),
    CheatDef(28, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_28_drunk),
    CheatDef(29, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_29_fast_swim),
    CheatDef(30, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_30_super_jump),
    CheatDef(31, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_31_explosive_ammo),
    CheatDef(32, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_32_explosive_melee),
    CheatDef(33, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_33_incendiary_ammo),
    CheatDef(34, isToggle = true,  games = CHEAT_V,    category = CheatCategory.Toggles,
             labelRes = R.string.cheat_34_slippery),
)

/** Cheats the current game supports, grouped by category and
 *  preserving each category's catalog order. Empty categories are
 *  excluded so CheatsScreen does not render headers for sections the
 *  current game has no cheats in. */
internal fun visibleCheats(isGtaV: Boolean): Map<CheatCategory, List<CheatDef>> {
    val wanted = if (isGtaV) CHEAT_V else CHEAT_IV
    val filtered = CHEATS.filter { it.games and wanted != 0 }
    val grouped = linkedMapOf<CheatCategory, List<CheatDef>>()
    for (category in CheatCategory.entries) {
        val items = filtered.filter { it.category == category }
        if (items.isNotEmpty()) grouped[category] = items
    }
    return grouped
}

/** True when toggle [id] is lit in [mask] (null mask = never). */
internal fun isOn(mask: Int?, id: Int): Boolean {
    if (mask == null) return false
    if (id < FIRST_TOGGLE_ID) return false
    return (mask ushr (id - FIRST_TOGGLE_ID)) and 1 == 1
}
