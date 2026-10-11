#pragma once

#include <cstddef>
#include <cstdint>

#include "joaat.h"

// Weapon ids the plugin knows by name. GTA V has no "what does this
// ped own" enumeration native, so the owned-weapon scan asks
// HAS_PED_GOT_WEAPON for each entry here; and the game has no native
// that turns a weapon hash back into text, so this table is also how
// `weapon_name` in the state frame gets filled in.
//
// A name that does not exist in the running game build is harmless:
// HAS_PED_GOT_WEAPON simply answers false. A weapon missing from this
// list still shows up in the state frame by hash (with an empty name)
// when equipped; the companion then labels it generically.
//
// Hashes are computed from the names at compile time (see joaat.h).

namespace weapons {

struct Entry {
    const char* name;
    uint32_t    hash;
};

#define W(n) { n, joaat(n) }
constexpr Entry kTable[] = {
    // melee
    W("weapon_knife"), W("weapon_nightstick"), W("weapon_hammer"),
    W("weapon_bat"), W("weapon_golfclub"), W("weapon_crowbar"),
    W("weapon_bottle"), W("weapon_dagger"), W("weapon_hatchet"),
    W("weapon_knuckle"), W("weapon_machete"), W("weapon_flashlight"),
    W("weapon_switchblade"), W("weapon_poolcue"), W("weapon_wrench"),
    W("weapon_battleaxe"), W("weapon_stone_hatchet"), W("weapon_candycane"),
    // handguns
    W("weapon_pistol"), W("weapon_pistol_mk2"), W("weapon_combatpistol"),
    W("weapon_appistol"), W("weapon_pistol50"), W("weapon_snspistol"),
    W("weapon_snspistol_mk2"), W("weapon_heavypistol"),
    W("weapon_vintagepistol"), W("weapon_marksmanpistol"),
    W("weapon_revolver"), W("weapon_revolver_mk2"), W("weapon_doubleaction"),
    W("weapon_stungun"), W("weapon_flaregun"), W("weapon_raypistol"),
    W("weapon_ceramicpistol"), W("weapon_navyrevolver"),
    W("weapon_gadgetpistol"), W("weapon_pistolxm3"), W("weapon_tecpistol"),
    // SMGs / MGs
    W("weapon_microsmg"), W("weapon_smg"), W("weapon_smg_mk2"),
    W("weapon_assaultsmg"), W("weapon_combatpdw"), W("weapon_machinepistol"),
    W("weapon_minismg"), W("weapon_raycarbine"), W("weapon_mg"),
    W("weapon_combatmg"), W("weapon_combatmg_mk2"), W("weapon_gusenberg"),
    // rifles
    W("weapon_assaultrifle"), W("weapon_assaultrifle_mk2"),
    W("weapon_carbinerifle"), W("weapon_carbinerifle_mk2"),
    W("weapon_advancedrifle"), W("weapon_specialcarbine"),
    W("weapon_specialcarbine_mk2"), W("weapon_bullpuprifle"),
    W("weapon_bullpuprifle_mk2"), W("weapon_compactrifle"),
    W("weapon_militaryrifle"), W("weapon_heavyrifle"),
    W("weapon_tacticalrifle"), W("weapon_battlerifle"),
    // shotguns
    W("weapon_pumpshotgun"), W("weapon_pumpshotgun_mk2"),
    W("weapon_sawnoffshotgun"), W("weapon_assaultshotgun"),
    W("weapon_bullpupshotgun"), W("weapon_musket"), W("weapon_heavyshotgun"),
    W("weapon_dbshotgun"), W("weapon_autoshotgun"), W("weapon_combatshotgun"),
    // sniper rifles
    W("weapon_sniperrifle"), W("weapon_heavysniper"),
    W("weapon_heavysniper_mk2"), W("weapon_marksmanrifle"),
    W("weapon_marksmanrifle_mk2"), W("weapon_precisionrifle"),
    // heavy
    W("weapon_rpg"), W("weapon_grenadelauncher"),
    W("weapon_grenadelauncher_smoke"), W("weapon_minigun"),
    W("weapon_firework"), W("weapon_railgun"), W("weapon_hominglauncher"),
    W("weapon_compactlauncher"), W("weapon_rayminigun"),
    W("weapon_emplauncher"), W("weapon_railgunxm3"),
    // thrown
    W("weapon_grenade"), W("weapon_bzgas"), W("weapon_molotov"),
    W("weapon_stickybomb"), W("weapon_proxmine"), W("weapon_snowball"),
    W("weapon_pipebomb"), W("weapon_ball"), W("weapon_smokegrenade"),
    W("weapon_flare"),
    // misc
    W("weapon_petrolcan"), W("weapon_fireextinguisher"),
    W("weapon_hazardcan"), W("weapon_fertilizercan"),
    W("gadget_parachute"),
};
#undef W

constexpr size_t kCount = sizeof(kTable) / sizeof(kTable[0]);
constexpr uint32_t kUnarmed = joaat("weapon_unarmed");

// The published owned-weapon list (snapshot::kMaxWeapons) must be able
// to hold every entry; checked in script.cpp where both are visible.

// Name for a hash, or nullptr when it is not in the table.
inline const char* name_of(uint32_t hash) {
    if (hash == kUnarmed) return "weapon_unarmed";
    for (size_t i = 0; i < kCount; ++i) {
        if (kTable[i].hash == hash) return kTable[i].name;
    }
    return nullptr;
}

}  // namespace weapons
