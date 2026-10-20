#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "cheats.h"
#include "commands.h"
#include "joaat.h"
#include "log.h"
#include "natives.h"
#include "shv.h"
#include "weapons.h"

namespace cheats {

const char* const kSpawnCarModels[kSpawnCarCount] = {
    "adder", "zentorno", "bati", "sanchez", "frogger", "bmx",
};

// Weather names from NativeDB's SET_WEATHER_TYPE_NOW_PERSIST comment.
// SNOW is seasonal / event-only on vanilla; keep it in the cycle but
// expect it to look like CLEAR outside the holiday window.
const char* const kWeatherNames[kWeatherCount] = {
    "EXTRASUNNY", "CLEAR", "CLOUDS", "OVERCAST",
    "RAIN", "THUNDER", "FOGGY", "SMOG", "SNOW",
};

const uint32_t kClockHours[kClockHourCount] = { 6, 12, 18, 0 };

const char* const kErrUnknown        = "unknown cheat";
const char* const kErrNotInBuild     = "cheat not in this build";
const char* const kErrNoPed          = "no player ped";
const char* const kErrNotInVehicle   = "not in a vehicle";
const char* const kErrSpecialLocked  = "special ability not unlocked";
const char* const kErrSpawnInProgress = "spawn in progress";
const char* const kErrModelBad       = "model not available";
const char* const kErrSpawnTimeout   = "model load timed out";

namespace {

// Toggle mask + cycle indices + session flag.
uint32_t g_mask = 0;
uint32_t g_weather_idx = 0;
bool     g_stat_marked = false;

// Pending spawn-car command. One at a time.
struct Spawn {
    bool     active;
    uint32_t model;         // joaat(name)
    uint32_t client;        // ack target
    uint32_t cmd_id;
    uint64_t deadline_ms;   // GetTickCount64()
};
Spawn g_spawn{};

bool is_on(uint32_t id) {
    const uint32_t bit = 1u << (id - kFirstToggle);
    return (g_mask & bit) != 0;
}

void set_toggle(uint32_t id, bool on) {
    const uint32_t bit = 1u << (id - kFirstToggle);
    if (on) g_mask |= bit;
    else    g_mask &= ~bit;
}

bool v_knows_toggle(uint32_t id) {
    switch (id) {
    case kInvincible:
    case kInfiniteClip:
    case kFastRun:
    case kLowGravity:
    case kSlowMotion:
    case kPoliceIgnore:
        return true;
    default:
        return false;
    }
}

bool v_knows_one_shot(uint32_t id) {
    switch (id) {
    case kHeal: case kWantedUp: case kWantedClear:
    case kAllWeapons: case kMaxAmmo: case kFixVehicle:
    case kNextWeather: case kNextTimeOfDay: case kFillSpecial:
        return true;
    default:
        return id >= kSpawnCarFirst && id <= kSpawnCarLast;
    }
}

// Apply a toggle's current state to the game via its native. Also
// called from tick() to re-apply every frame while on.
void apply_toggle(uint32_t id, int32_t player, int32_t ped) {
    const bool on = is_on(id);
    switch (id) {
    case kInvincible:
        shv::call<void>(natives::SET_PLAYER_INVINCIBLE_BUT_HAS_REACTIONS,
                        player, on);
        break;
    case kInfiniteClip:
        shv::call<void>(natives::SET_PED_INFINITE_AMMO_CLIP, ped, on);
        break;
    case kFastRun:
        shv::call<void>(natives::SET_RUN_SPRINT_MULTIPLIER_FOR_PLAYER,
                        player, on ? kFastRunMult : 1.0f);
        break;
    case kLowGravity:
        shv::call<void>(natives::SET_GRAVITY_LEVEL,
                        on ? kLowGravityLevel : 0);
        break;
    case kSlowMotion:
        shv::call<void>(natives::SET_TIME_SCALE,
                        on ? kSlowTimeScale : 1.0f);
        break;
    case kPoliceIgnore:
        shv::call<void>(natives::SET_POLICE_IGNORE_PLAYER, player, on);
        break;
    default:
        break;
    }
}

// Set STAT_SET_CHEAT_IS_ACTIVE once per session the first time any
// cheat fires. The game uses this to block achievements for the
// session, matching what entering a cheat the normal way would do.
void mark_session_cheated() {
    if (g_stat_marked) return;
    g_stat_marked = true;
    shv::call<void>(natives::STAT_SET_CHEAT_IS_ACTIVE);
    thor_log("[cheats] session marked cheat-active");
}

void run_heal(int32_t player, int32_t ped) {
    const int32_t max_h = shv::call<int32_t>(natives::GET_ENTITY_MAX_HEALTH, ped);
    const int32_t max_a = shv::call<int32_t>(natives::GET_PLAYER_MAX_ARMOUR, player);
    // SET_ENTITY_HEALTH takes four args on both editions: Entity,
    // health, instigator (0 = none), weaponType (0 = none). Legacy
    // SDKs took two; the four-arg form is current.
    shv::call<void>(natives::SET_ENTITY_HEALTH, ped, max_h, 0, 0u);
    shv::call<void>(natives::SET_PED_ARMOUR, ped, max_a);
}

void run_wanted_up(int32_t player) {
    const int32_t cur = shv::call<int32_t>(natives::GET_PLAYER_WANTED_LEVEL, player);
    int32_t next = cur + 1;
    if (next > 5) next = 5;
    shv::call<void>(natives::SET_PLAYER_WANTED_LEVEL, player, next, 0);
    shv::call<void>(natives::SET_PLAYER_WANTED_LEVEL_NOW, player, 0);
}

void run_all_weapons(int32_t ped) {
    for (size_t i = 0; i < weapons::kCount; ++i) {
        shv::call<void>(natives::GIVE_WEAPON_TO_PED, ped,
                        weapons::kTable[i].hash, kFullAmmo, 0, 0);
    }
}

void run_max_ammo(int32_t ped) {
    // Walk the known weapon table; HAS_PED_GOT_WEAPON gates each one so
    // SET_PED_AMMO is never written against a weapon the ped does not
    // carry.
    for (size_t i = 0; i < weapons::kCount; ++i) {
        const uint32_t hash = weapons::kTable[i].hash;
        if (!shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, hash, 0)) {
            continue;
        }
        int32_t cap = 0;
        const bool ok = shv::call_bool(natives::GET_MAX_AMMO, ped, hash, &cap);
        if (!ok || cap <= 0) cap = kFullAmmo;
        shv::call<void>(natives::SET_PED_AMMO, ped, hash, cap, 0);
    }
}

const char* run_fix_vehicle(int32_t ped, const snapshot::State& s) {
    if (!s.in_vehicle) return kErrNotInVehicle;
    const int32_t veh = shv::call<int32_t>(natives::GET_VEHICLE_PED_IS_IN, ped, 0);
    if (veh == 0) return kErrNotInVehicle;
    shv::call<void>(natives::SET_VEHICLE_FIXED, veh);
    shv::call<void>(natives::SET_VEHICLE_ENGINE_HEALTH, veh, kFullBodyHealth);
    shv::call<void>(natives::SET_VEHICLE_BODY_HEALTH, veh, kFullBodyHealth);
    shv::call<void>(natives::SET_VEHICLE_DIRT_LEVEL, veh, 0.0f);
    return nullptr;
}

void run_next_weather() {
    // Cycle through the named weathers, with a final step that hands
    // control back to the game. Wrap on the next tap.
    if (g_weather_idx < kWeatherCount) {
        shv::call<void>(natives::SET_WEATHER_TYPE_NOW_PERSIST,
                        kWeatherNames[g_weather_idx]);
        ++g_weather_idx;
    } else {
        shv::call<void>(natives::CLEAR_WEATHER_TYPE_PERSIST);
        g_weather_idx = 0;
    }
}

void run_next_time() {
    const int32_t h = shv::call<int32_t>(natives::GET_CLOCK_HOURS);
    uint32_t next = kClockHours[0];
    for (uint32_t i = 0; i < kClockHourCount; ++i) {
        // Treat 0 as 24 for monotone comparison so {6,12,18,0} wraps.
        const uint32_t sortable = kClockHours[i] == 0 ? 24u : kClockHours[i];
        if (sortable > static_cast<uint32_t>(h)) {
            next = kClockHours[i];
            break;
        }
    }
    shv::call<void>(natives::SET_CLOCK_TIME, static_cast<int32_t>(next), 0, 0);
}

const char* run_fill_special(int32_t player, const snapshot::State& s) {
    if (!s.special_unlocked) return kErrSpecialLocked;
    shv::call<void>(natives::SPECIAL_ABILITY_FILL_METER, player, 1, 0);
    return nullptr;
}

// Kick off the async spawn. Returns an err string on refusal, nullptr
// when the state machine has been armed. The ack is posted from tick()
// when the machine resolves — the caller must not post one itself.
const char* begin_spawn(uint32_t id, uint32_t client, uint32_t cmd_id) {
    if (g_spawn.active) return kErrSpawnInProgress;
    const uint32_t slot = id - kSpawnCarFirst;
    if (slot >= kSpawnCarCount) return kErrUnknown;
    const uint32_t hash = joaat(kSpawnCarModels[slot]);
    if (!shv::call_bool(natives::IS_MODEL_IN_CDIMAGE, hash) ||
        !shv::call_bool(natives::IS_MODEL_A_VEHICLE, hash)) {
        return kErrModelBad;
    }
    shv::call<void>(natives::REQUEST_MODEL, hash);
    g_spawn.active = true;
    g_spawn.model = hash;
    g_spawn.client = client;
    g_spawn.cmd_id = cmd_id;
    g_spawn.deadline_ms = GetTickCount64() + kSpawnTimeoutMs;
    thor_log("[cheat] spawn %s (0x%08X) requested", kSpawnCarModels[slot], hash);
    return nullptr;
}

// Advance the spawn state machine. Called every tick. Posts the
// deferred ack when the spawn resolves (success or timeout).
void advance_spawn(int32_t ped) {
    if (!g_spawn.active) return;
    if (ped == 0) {
        // Ped went away mid-spawn (loading, online). Release and bail.
        shv::call<void>(natives::SET_MODEL_AS_NO_LONGER_NEEDED, g_spawn.model);
        commands::post_ack(g_spawn.client, g_spawn.cmd_id, false, kErrNoPed);
        g_spawn = Spawn{};
        return;
    }
    const bool loaded = shv::call_bool(natives::HAS_MODEL_LOADED, g_spawn.model);
    if (!loaded) {
        if (GetTickCount64() > g_spawn.deadline_ms) {
            shv::call<void>(natives::SET_MODEL_AS_NO_LONGER_NEEDED, g_spawn.model);
            thor_log("[cheat] spawn 0x%08X -> %s", g_spawn.model, kErrSpawnTimeout);
            commands::post_ack(g_spawn.client, g_spawn.cmd_id, false, kErrSpawnTimeout);
            g_spawn = Spawn{};
        }
        return;
    }
    // Loaded. Place the car in front of the ped so it does not land on
    // the player's head; settle it to the ground; seat the ped.
    const shv::Vec3 c = shv::call_vec3(natives::GET_ENTITY_COORDS, ped, 1);
    const float h = shv::call<float>(natives::GET_ENTITY_HEADING, ped);
    // Heading is degrees clockwise from north (y+). Forward unit:
    //   fx = -sin(h), fy = cos(h)
    const float rad = h * 3.14159265f / 180.0f;
    const float fx = -__builtin_sinf(rad), fy = __builtin_cosf(rad);
    const float sx = c.x + fx * kSpawnForwardM;
    const float sy = c.y + fy * kSpawnForwardM;
    const int32_t veh = shv::call<int32_t>(natives::CREATE_VEHICLE,
                                           g_spawn.model, sx, sy, c.z,
                                           h, 0, 0, 0);
    shv::call_bool(natives::SET_VEHICLE_ON_GROUND_PROPERLY, veh, 5.0f);
    shv::call<void>(natives::SET_PED_INTO_VEHICLE, ped, veh, -1);
    // Hand the model and the vehicle back to the game so it can clean
    // them up when the player drives away. SET_VEHICLE_AS_NO_LONGER_
    // NEEDED takes a Vehicle*; pass a local by address.
    int32_t veh_handle = veh;
    shv::call<void>(natives::SET_VEHICLE_AS_NO_LONGER_NEEDED, &veh_handle);
    shv::call<void>(natives::SET_MODEL_AS_NO_LONGER_NEEDED, g_spawn.model);
    thor_log("[cheat] spawn 0x%08X -> ok (veh=%d)", g_spawn.model, veh);
    commands::post_ack(g_spawn.client, g_spawn.cmd_id, true, "");
    g_spawn = Spawn{};
}

}  // namespace

void run(uint32_t id, int32_t player, int32_t ped,
         const snapshot::State& s, uint32_t client, uint32_t cmd_id) {
    if (ped == 0) {
        commands::post_ack(client, cmd_id, false, kErrNoPed);
        return;
    }
    if (id == 0 || id > kMaxToggle) {
        commands::post_ack(client, cmd_id, false, kErrUnknown);
        return;
    }

    // Toggles.
    if (id >= kFirstToggle) {
        if (!v_knows_toggle(id)) {
            commands::post_ack(client, cmd_id, false, kErrNotInBuild);
            return;
        }
        mark_session_cheated();
        set_toggle(id, !is_on(id));
        apply_toggle(id, player, ped);
        thor_log("[cheat] id=%u (toggle) -> ok (mask=0x%08X)", id, g_mask);
        commands::post_ack(client, cmd_id, true, "");
        return;
    }

    // One-shots.
    if (id >= kSpawnCarFirst && id <= kSpawnCarLast) {
        if (const char* why = begin_spawn(id, client, cmd_id)) {
            thor_log("[cheat] id=%u (spawn) -> %s", id, why);
            commands::post_ack(client, cmd_id, false, why);
        }
        // Successful begin: ack is deferred to advance_spawn().
        if (g_spawn.active) mark_session_cheated();
        return;
    }

    if (!v_knows_one_shot(id)) {
        commands::post_ack(client, cmd_id, false, kErrNotInBuild);
        return;
    }

    const char* err = nullptr;
    switch (id) {
    case kHeal:          run_heal(player, ped); break;
    case kWantedUp:      run_wanted_up(player); break;
    case kWantedClear:   shv::call<void>(natives::CLEAR_PLAYER_WANTED_LEVEL, player); break;
    case kAllWeapons:    run_all_weapons(ped); break;
    case kMaxAmmo:       run_max_ammo(ped); break;
    case kFixVehicle:    err = run_fix_vehicle(ped, s); break;
    case kNextWeather:   run_next_weather(); break;
    case kNextTimeOfDay: run_next_time(); break;
    case kFillSpecial:   err = run_fill_special(player, s); break;
    default:             err = kErrUnknown; break;
    }

    if (err) {
        thor_log("[cheat] id=%u -> %s", id, err);
        commands::post_ack(client, cmd_id, false, err);
    } else {
        mark_session_cheated();
        thor_log("[cheat] id=%u -> ok (mask=0x%08X)", id, g_mask);
        commands::post_ack(client, cmd_id, true, "");
    }
}

void tick(int32_t player, int32_t ped) {
    if (ped == 0) return;

    // Re-apply every active toggle. Per-ped ones use the current ped so
    // a character switch does not clear them; world ones re-apply
    // because the game resets them on some transitions.
    if (g_mask != 0) {
        for (uint32_t id = kFirstToggle; id <= kMaxToggle; ++id) {
            if (is_on(id) && v_knows_toggle(id)) {
                apply_toggle(id, player, ped);
            }
        }
    }

    advance_spawn(ped);
}

void reset_world() {
    shv::call<void>(natives::SET_TIME_SCALE, 1.0f);
    shv::call<void>(natives::CLEAR_WEATHER_TYPE_PERSIST);
    shv::call<void>(natives::SET_GRAVITY_LEVEL, 0);
    thor_log("[cheats] world reset (mask kept 0x%08X)", g_mask);
}

uint32_t mask() { return g_mask; }

void reset() {
    g_mask = 0;
    g_weather_idx = 0;
    g_stat_marked = false;
    g_spawn = Spawn{};
}

}  // namespace cheats
