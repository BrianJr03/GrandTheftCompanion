#include "cheats.h"

#include "cheat_natives.h"
#include "weapons.h"

namespace cheats {

// IV weather enum, as used by vanilla scripts and the trainers that
// call FORCE_WEATHER_NOW. The last entry hands control back to the
// game via RELEASE_WEATHER (see run()). On-device test confirms the
// labels; the ids themselves are predictions from the task spec.
const uint32_t kWeatherIds[kWeatherCount] = {
    0,  // extrasunny
    1,  // sunny
    2,  // sunny-windy
    3,  // cloudy
    4,  // rain
    5,  // drizzle
    6,  // foggy
    7,  // lightning
    8,  // extrasunny-2
    9,  // sunny-2
};

const uint32_t kClockHours[kClockHourCount] = { 6, 12, 18, 0 };

// Base-IV weapon ids 1..20 minus 0 (unarmed) and 8 (empty slot in the
// eWeaponType enum). Episode-DLC ids past 20 are gated by episode and
// are not in v1.
const uint32_t kAllWeaponsIds[kAllWeaponsCount] = {
    1,  2,  3,  4,  5,  6,  7,
    /* skip 8 (empty), 0 (unarmed) */
    9,  10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
};

// Rockstar's "advanced weapons" phone cheat (486-555-0100), base IV.
// bat, combat pistol (deagle slot), combat shotgun, SMG, carbine,
// combat sniper, grenades, RPG.
const uint32_t kAdvancedWeaponsIds[kAdvancedWeaponsCount] = {
    1, 9, 11, 13, 15, 17, 4, 18,
};

// Rockstar's "poor weapons" phone cheat (486-555-0150), base IV.
// knife, pistol, pump shotgun, micro-SMG, assault rifle, combat
// sniper, molotov, RPG.
const uint32_t kPoorWeaponsIds[kPoorWeaponsCount] = {
    3, 7, 10, 12, 14, 17, 5, 18,
};

const char* const kErrUnknown      = "unknown cheat";
const char* const kErrNotInBuild   = "cheat not in this build";
const char* const kErrNoPed        = "no player ped";
const char* const kErrNotInVehicle = "not in a vehicle";

namespace {

uint32_t g_mask = 0;
uint32_t g_last_ped = 0;
uint32_t g_weather_idx = 0;

// Set / clear a toggle bit in the mask. Returns the new state.
bool set_toggle(uint32_t id, bool on) {
    const uint32_t bit = 1u << (id - kFirstToggle);
    if (on) g_mask |= bit;
    else    g_mask &= ~bit;
    return on;
}

bool is_on(uint32_t id) {
    const uint32_t bit = 1u << (id - kFirstToggle);
    return (g_mask & bit) != 0;
}

// Apply a toggle's current state to the game via its native. Also
// called from tick() to re-apply when the ped changed.
void apply_toggle(uint32_t id, uint32_t player, uint32_t ped) {
    const bool on = is_on(id);
    switch (id) {
    case kInvincible:
        game::set_player_invincible(player, on);
        break;
    case kFastReload:
        game::set_player_fast_reload(player, on);
        break;
    case kNeverTired:
        game::set_player_never_gets_tired(player, on);
        break;
    case kSlowMotion:
        // Time scale is a world setting, not per-player. The mask
        // still carries it so re-entering in-game restores it.
        game::set_time_scale(on ? 0.5f : 1.0f);
        break;
    case kPoliceIgnore:
        game::set_police_ignore_player(player, on);
        break;
    default:
        break;
    }
    (void)ped;  // every current toggle is player-indexed, not ped-indexed
}

// Give the full base-IV loadout. Skips the slot-0 and slot-8 holes.
void give_all_weapons(uint32_t ped) {
    for (uint32_t i = 0; i < kAllWeaponsCount; ++i) {
        game::give_weapon_to_char(ped, kAllWeaponsIds[i], kFullAmmo);
    }
    weapons::force_rescan();
}

void give_from_table(uint32_t ped, const uint32_t* ids, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        game::give_weapon_to_char(ped, ids[i], kFullAmmo);
    }
    weapons::force_rescan();
}

// Max out ammo for every weapon the last scan found. Scan may be empty
// in builds without level 4 (p.owned_valid is false) — then nothing to
// do, no fallback: writing SET_CHAR_AMMO against a weapon the ped does
// not carry is undefined.
void max_ammo(uint32_t ped, const snapshot::Player& p) {
    if (!p.owned_valid) return;
    for (int slot = 0; slot < 13; ++slot) {
        const int32_t w = p.owned_weapons[slot];
        if (w <= 0) continue;
        game::set_char_ammo(ped, static_cast<uint32_t>(w), kFullAmmo);
    }
}

const char* run_one_shot(uint32_t id, uint32_t player, uint32_t ped,
                         const snapshot::Player& p) {
    switch (id) {
    case kHeal:
        game::set_char_health(ped, kMaxHealth);
        game::add_armour_to_char(ped, kMaxArmour);
        return nullptr;
    case kWantedUp: {
        const uint32_t cap = game::get_max_wanted_level();
        uint32_t next = static_cast<uint32_t>(p.wanted_level) + 1;
        if (cap > 0 && next > cap) next = cap;
        game::alter_wanted_level(player, next);
        game::apply_wanted_level_change_now(player);
        return nullptr;
    }
    case kWantedClear:
        game::clear_wanted_level(player);
        return nullptr;
    case kAllWeapons:
        give_all_weapons(ped);
        return nullptr;
    case kMaxAmmo:
        max_ammo(ped, p);
        return nullptr;
    case kFixVehicle: {
        if (!p.in_vehicle) return kErrNotInVehicle;
        const uint32_t veh = game::get_car_char_is_using(ped);
        if (veh == 0) return kErrNotInVehicle;
        game::fix_car(veh);
        game::wash_vehicle_textures(veh, 0);
        return nullptr;
    }
    case kNextWeather:
        // Advance, cycling through kWeatherIds and ending on
        // RELEASE_WEATHER (the "let the game decide" step).
        if (g_weather_idx < kWeatherCount) {
            game::force_weather_now(kWeatherIds[g_weather_idx]);
            ++g_weather_idx;
        } else {
            game::release_weather();
            g_weather_idx = 0;
        }
        return nullptr;
    case kNextTimeOfDay: {
        uint32_t h = 0, m = 0;
        game::get_time_of_day(&h, &m);
        // Pick the first hour in kClockHours strictly greater than h,
        // else wrap to kClockHours[0].
        uint32_t next = kClockHours[0];
        bool chosen = false;
        for (uint32_t i = 0; i < kClockHourCount; ++i) {
            // The cycle is sorted ascending apart from the trailing 0,
            // which wraps past the last positive entry. Treat 0 as 24
            // for the "next" comparison so {6,12,18,0} is monotone.
            const uint32_t sortable = kClockHours[i] == 0 ? 24u : kClockHours[i];
            if (sortable > h) { next = kClockHours[i]; chosen = true; break; }
        }
        (void)chosen;
        game::set_time_of_day(next, 0);
        return nullptr;
    }
    case kGiveParachute:
        game::give_weapon_to_char(ped, kWeaponParachute, 1);
        return nullptr;
    case kAdvancedWeapons:
        give_from_table(ped, kAdvancedWeaponsIds, kAdvancedWeaponsCount);
        return nullptr;
    case kPoorWeapons:
        give_from_table(ped, kPoorWeaponsIds, kPoorWeaponsCount);
        return nullptr;
    case kUltimate:
        // Phone cheat 482-555-0100: health + armor + ammo + repair +
        // advanced weapons. Repair only applies when in a vehicle;
        // same rule as kFixVehicle.
        game::set_char_health(ped, kMaxHealth);
        game::add_armour_to_char(ped, kMaxArmour);
        max_ammo(ped, p);
        if (p.in_vehicle) {
            const uint32_t veh = game::get_car_char_is_using(ped);
            if (veh != 0) {
                game::fix_car(veh);
                game::wash_vehicle_textures(veh, 0);
            }
        }
        give_from_table(ped, kAdvancedWeaponsIds, kAdvancedWeaponsCount);
        return nullptr;
    default:
        return kErrNotInBuild;
    }
}

bool iv_knows_toggle(uint32_t id) {
    switch (id) {
    case kInvincible:
    case kFastReload:
    case kNeverTired:
    case kSlowMotion:
    case kPoliceIgnore:
        return true;
    default:
        return false;
    }
}

}  // namespace

const char* run(uint32_t id, uint32_t player, uint32_t ped,
                const snapshot::Player& p) {
    if (ped == 0 || ped == 0xFFFFFFFFu) return kErrNoPed;
    if (id == 0) return kErrUnknown;

    // Toggle ids first — they stay in a contiguous range.
    if (id >= kFirstToggle && id <= kMaxToggle) {
        if (!iv_knows_toggle(id)) return kErrNotInBuild;
        set_toggle(id, !is_on(id));
        apply_toggle(id, player, ped);
        g_last_ped = ped;
        return nullptr;
    }

    // Scalar one-shots split into two bands around the toggle range so
    // new scalar cheats can land past the toggle id space without
    // reshuffling what the device test has already proven.
    const bool is_one_shot =
        (id <= kMaxLowOneShot) ||
        (id >= kFirstHighOneShot && id <= kMaxHighOneShot);
    if (!is_one_shot) return kErrUnknown;

    const char* err = run_one_shot(id, player, ped, p);
    if (!err) g_last_ped = ped;
    return err;
}

void tick(uint32_t player, uint32_t ped) {
    if (ped == 0 || ped == 0xFFFFFFFFu) return;
    if (ped == g_last_ped) return;
    g_last_ped = ped;
    if (g_mask == 0) return;
    // Re-apply every toggle whose bit is set. World toggles (slow
    // motion) re-apply the time scale too — the game may have reset
    // it across the ped change.
    for (uint32_t id = kFirstToggle; id < kFirstToggle + 12; ++id) {
        if (is_on(id) && iv_knows_toggle(id)) {
            apply_toggle(id, player, ped);
        }
    }
}

void reset_world() {
    // World settings the game resets on its own on quit-to-menu / load.
    // Mask is kept so re-entering in-game re-applies them.
    game::set_time_scale(1.0f);
    game::release_weather();
}

uint32_t mask() { return g_mask; }

void reset() {
    g_mask = 0;
    g_last_ped = 0;
    g_weather_idx = 0;
}

}  // namespace cheats
