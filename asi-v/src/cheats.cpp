#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstdlib>
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

// Random-spawn curated lists for the Trainer one-shots. Short on
// purpose; Phase 3 will land the full vehicle library. The cop list
// skips story-only or restricted models so a tap in free roam never
// refuses with kErrModelBad.
constexpr uint32_t kCopPedCount = 3;
const char* const kCopPedModels[kCopPedCount] = {
    "s_m_y_cop_01", "s_m_y_sheriff_01", "s_m_y_hwaycop_01",
};
constexpr uint32_t kRandomBoatCount = 5;
const char* const kRandomBoatModels[kRandomBoatCount] = {
    "speeder", "dinghy", "seashark", "marquis", "suntrap",
};
constexpr uint32_t kRandomPlaneCount = 5;
const char* const kRandomPlaneModels[kRandomPlaneCount] = {
    "jet", "cuban800", "duster", "luxor", "mammatus",
};
constexpr uint32_t kRandomHeliCount = 5;
const char* const kRandomHeliModels[kRandomHeliCount] = {
    "buzzard", "cargobob", "maverick", "polmav", "savage",
};

// Fixed radius around the player for the clear-area action. Tight
// enough that the player's current vehicle stays, wide enough that
// hostile peds and parked cars within view clear out.
constexpr float kClearAreaRadiusM = 20.0f;
// Portal teleport distance in metres, measured along the gameplay
// camera's forward vector. 5 m is roughly one car length, enough to
// get through a locked door, short enough that the player doesn't
// lose their bearings.
constexpr float kPortalForwardM   = 5.0f;
// CREATE_PED pedType for a cop ped. Values from NativeDB:
//   0 player, 4 cop, 5 human, 6 swat. 4 is the right slot for a
// civilian-shaped cop model (s_m_y_cop_01); swat (6) would give the
// spawned ped MP5 loadout which we don't want for the default tap.
constexpr int32_t kPedTypeCop = 4;
// Ragdoll duration per call. We re-fire the native every tick while
// the toggle is on; a 2 s window is plenty since the tick runs at
// 25 Hz (one call per 40 ms).
constexpr int32_t kRagdollDurationMs = 2000;

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
const char* const kErrNotInGame      = "not in this game";

namespace {

// Toggle mask + cycle indices + session flag.
uint32_t g_mask = 0;
uint32_t g_weather_idx = 0;
bool     g_stat_marked = false;
// Seeded once from GetTickCount on the first respray tap. Not for
// cryptography; just so two sessions don't start on the same colour.
bool     g_rand_seeded = false;

// What kind of entity the pending async spawn produces. The state
// machine in advance_spawn branches on this: vehicles seat the player
// and call SET_VEHICLE_ON_GROUND_PROPERLY; peds set a heading and
// optionally arm them.
enum SpawnKind : uint8_t {
    kSpawnVehicle,
    kSpawnPed,
};

// Pending async spawn. One at a time; a second tap while one is
// pending is refused with kErrSpawnInProgress. Covers both the
// curated kSpawnCarFirst..kSpawnCarLast ids and the Trainer random
// spawns (kRandomCop, kRandomBoat, kRandomPlane, kRandomHeli).
struct Spawn {
    bool     active;
    SpawnKind kind;
    uint32_t model;         // joaat(name)
    uint32_t client;        // ack target
    uint32_t cmd_id;
    uint64_t deadline_ms;   // GetTickCount64()
};
Spawn g_spawn{};

// Seed std::rand once per session, lazily. Same guard as g_rand_seeded
// so the two paths share one seed instead of re-seeding each other.
void ensure_rand_seeded() {
    if (g_rand_seeded) return;
    g_rand_seeded = true;
    std::srand(static_cast<unsigned>(GetTickCount64()));
}

uint32_t pick_random(const char* const* table, uint32_t count) {
    ensure_rand_seeded();
    return joaat(table[static_cast<uint32_t>(std::rand()) % count]);
}

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
    case kDrunkMode:
    case kFastSwim:
    case kSuperJump:
    case kExplosiveAmmo:
    case kExplosiveMelee:
    case kIncendiaryAmmo:
    case kSlipperyVehicles:
    case kRagdoll:
    case kInvisible:
    case kInfiniteAmmo:
    case kInvincibleVehicle:
    case kPauseTime:
        return true;
    default:
        return false;
    }
}

bool v_knows_one_shot(uint32_t id) {
    switch (id) {
    case kHeal: case kWantedUp: case kWantedClear: case kWantedDown:
    case kAllWeapons: case kMaxAmmo: case kFixVehicle:
    case kNextWeather: case kNextTimeOfDay: case kFillSpecial:
    case kGiveParachute: case kSkyfall:
    case kRespray: case kNextColour: case kSwapColours:
    case kCustomColour:
    case kClearArea: case kPortal:
        return true;
    default:
        return (id >= kSpawnCarFirst && id <= kSpawnCarLast)
            || id == kRandomCop || id == kRandomBoat
            || id == kRandomPlane || id == kRandomHeli;
    }
}

// Shared time-scale write for kSlowMotion + kPauseTime. Pause wins if
// both bits are on; otherwise slow wins if its bit is on; otherwise
// 1.0 (normal). Called from apply_toggle for either id. The write is
// idempotent, so re-applying every tick keeps the scale correct even
// after game-side resets.
void apply_time_scale() {
    const bool pause = is_on(kPauseTime);
    const bool slow  = is_on(kSlowMotion);
    float scale = 1.0f;
    if (pause)      scale = 0.0f;
    else if (slow)  scale = kSlowTimeScale;
    shv::call<void>(natives::SET_TIME_SCALE, scale);
}

// Shared infinite-clip write for kInfiniteClip + kInfiniteAmmo. The
// clip native covers both toggles' "never reload" semantics; the
// reserve-ammo side rides on SET_PED_INFINITE_AMMO calls per weapon
// group in apply_toggle for kInfiniteAmmo. Leaving the clip-side
// shared keeps a toggle-off tap from writing false while the other
// bit is still on.
void apply_infinite_clip(int32_t ped) {
    const bool on = is_on(kInfiniteClip) || is_on(kInfiniteAmmo);
    shv::call<void>(natives::SET_PED_INFINITE_AMMO_CLIP, ped, on);
}

// Apply a toggle's current state to the game via its native. Also
// called from tick() to re-apply every frame while on. "This frame"
// natives (SET_SUPER_JUMP_THIS_FRAME and friends) are called only
// when `on`; turning the toggle off means stop calling them, which
// is the game's own convention — the effect ends on the next frame.
void apply_toggle(uint32_t id, int32_t player, int32_t ped,
                  const snapshot::State* s) {
    const bool on = is_on(id);
    switch (id) {
    case kInvincible:
        shv::call<void>(natives::SET_PLAYER_INVINCIBLE_BUT_HAS_REACTIONS,
                        player, on);
        break;
    case kInfiniteClip:
    case kInfiniteAmmo:
        // Both toggles share SET_PED_INFINITE_AMMO_CLIP (clip never
        // empties). kInfiniteAmmo additionally walks the weapon table
        // and calls SET_PED_INFINITE_AMMO per owned weapon so the
        // reserve pool also stops depleting.
        apply_infinite_clip(ped);
        if (id == kInfiniteAmmo) {
            for (size_t i = 0; i < weapons::kCount; ++i) {
                const uint32_t hash = weapons::kTable[i].hash;
                if (!shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, hash, 0)) {
                    continue;
                }
                shv::call<void>(natives::SET_PED_INFINITE_AMMO,
                                ped, on, hash);
            }
        }
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
    case kPauseTime:
        // Shared SET_TIME_SCALE write: pause > slow > normal. Both
        // cases route through the same helper so flipping one toggle
        // does not stomp the other's state.
        apply_time_scale();
        break;
    case kPoliceIgnore:
        shv::call<void>(natives::SET_POLICE_IGNORE_PLAYER, player, on);
        break;
    case kDrunkMode:
        shv::call<void>(natives::SET_PED_IS_DRUNK, ped, on);
        break;
    case kFastSwim:
        shv::call<void>(natives::SET_SWIM_MULTIPLIER_FOR_PLAYER,
                        player, on ? kFastSwimMult : 1.0f);
        break;
    case kSuperJump:
        if (on) shv::call<void>(natives::SET_SUPER_JUMP_THIS_FRAME, player);
        break;
    case kExplosiveAmmo:
        if (on) shv::call<void>(natives::SET_EXPLOSIVE_AMMO_THIS_FRAME, player);
        break;
    case kExplosiveMelee:
        if (on) shv::call<void>(natives::SET_EXPLOSIVE_MELEE_THIS_FRAME, player);
        break;
    case kIncendiaryAmmo:
        if (on) shv::call<void>(natives::SET_FIRE_AMMO_THIS_FRAME, player);
        break;
    case kSlipperyVehicles:
        // Vehicle-targeted: applies to whatever the player is in. A
        // player on foot still carries the mask bit; the native fires
        // next time they get in a car.
        if (s && s->in_vehicle) {
            const int32_t veh = shv::call<int32_t>(natives::GET_VEHICLE_PED_IS_IN,
                                                   ped, 0);
            if (veh != 0) {
                shv::call<void>(natives::SET_VEHICLE_REDUCE_GRIP, veh, on);
            }
        }
        break;
    case kRagdoll:
        // Re-fire every tick so the ped stays down: SET_PED_TO_RAGDOLL
        // ragdolls for the duration window then returns to AI control.
        // The 2 s window is long enough that even a laggy tick keeps
        // the player prone until they flip the toggle off.
        if (on) {
            shv::call<bool>(natives::SET_PED_TO_RAGDOLL, ped,
                            kRagdollDurationMs, kRagdollDurationMs,
                            0, 1, 1, 0);
        }
        break;
    case kInvisible:
        // NativeDB: param 3 is the "animated" flag; false hides the
        // ped including shadows. Visible path writes true, matching a
        // normal ped.
        shv::call<void>(natives::SET_ENTITY_VISIBLE, ped, !on, 0);
        break;
    case kInvincibleVehicle:
        // Mirrors kSlipperyVehicles: only has a target when the ped
        // is in a vehicle; the bit stays lit so getting into a new
        // car re-applies on the next tick.
        if (s && s->in_vehicle) {
            const int32_t veh = shv::call<int32_t>(natives::GET_VEHICLE_PED_IS_IN,
                                                   ped, 0);
            if (veh != 0) {
                shv::call<void>(natives::SET_ENTITY_INVINCIBLE, veh, on);
            }
        }
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

void run_wanted_down(int32_t player) {
    const int32_t cur = shv::call<int32_t>(natives::GET_PLAYER_WANTED_LEVEL, player);
    int32_t next = cur - 1;
    if (next < 0) next = 0;
    shv::call<void>(natives::SET_PLAYER_WANTED_LEVEL, player, next, 0);
    shv::call<void>(natives::SET_PLAYER_WANTED_LEVEL_NOW, player, 0);
}

void run_give_parachute(int32_t ped) {
    const uint32_t parachute = joaat("gadget_parachute");
    shv::call<void>(natives::GIVE_WEAPON_TO_PED, ped, parachute, 1, 0, 0);
}

void run_skyfall(int32_t ped) {
    // Teleport straight up over the player's current spot and let
    // gravity do the rest. The parachute cheat is adjacent for a
    // reason; nothing stops the player from tapping both back-to-back.
    const shv::Vec3 c = shv::call_vec3(natives::GET_ENTITY_COORDS, ped, 1);
    shv::call<void>(natives::SET_ENTITY_COORDS, ped,
                    c.x, c.y, kSkyfallZ, 0, 0, 0, 1);
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

// Resolve the player's current vehicle, or return nullptr if the paint
// cheat is refused (on foot). Shared by respray / next / swap / custom.
const char* get_player_veh(int32_t ped, const snapshot::State& s,
                           int32_t* out_veh) {
    if (!s.in_vehicle) return kErrNotInVehicle;
    const int32_t veh = shv::call<int32_t>(natives::GET_VEHICLE_PED_IS_IN, ped, 0);
    if (veh == 0) return kErrNotInVehicle;
    *out_veh = veh;
    return nullptr;
}

// Clear any RGB override on either layer. Harmless when there was none
// (the native is a plain write to the custom-colour flag), so it's
// unconditional before every palette-index write.
void clear_custom_layers(int32_t veh) {
    shv::call<void>(natives::CLEAR_VEHICLE_CUSTOM_PRIMARY_COLOUR, veh);
    shv::call<void>(natives::CLEAR_VEHICLE_CUSTOM_SECONDARY_COLOUR, veh);
}

const char* run_respray(int32_t ped, const snapshot::State& s) {
    int32_t veh = 0;
    if (const char* why = get_player_veh(ped, s, &veh)) return why;
    if (!g_rand_seeded) {
        g_rand_seeded = true;
        std::srand(static_cast<unsigned>(GetTickCount64()));
    }
    const int32_t c1 = std::rand() % (static_cast<int32_t>(kPaletteMax) + 1);
    const int32_t c2 = std::rand() % (static_cast<int32_t>(kPaletteMax) + 1);
    clear_custom_layers(veh);
    shv::call<void>(natives::SET_VEHICLE_COLOURS, veh, c1, c2);
    // Reuse the fix path so a respray repairs + washes, like the shop.
    shv::call<void>(natives::SET_VEHICLE_FIXED, veh);
    shv::call<void>(natives::SET_VEHICLE_ENGINE_HEALTH, veh, kFullBodyHealth);
    shv::call<void>(natives::SET_VEHICLE_BODY_HEALTH, veh, kFullBodyHealth);
    shv::call<void>(natives::SET_VEHICLE_DIRT_LEVEL, veh, 0.0f);
    thor_log("[cheat] respray (60) -> ok c1=%d c2=%d", c1, c2);
    return nullptr;
}

const char* run_next_colour(int32_t ped, const snapshot::State& s) {
    int32_t veh = 0;
    if (const char* why = get_player_veh(ped, s, &veh)) return why;
    int32_t c1 = 0, c2 = 0;
    shv::call<void>(natives::GET_VEHICLE_COLOURS, veh, &c1, &c2);
    const int32_t next = (c1 + 1) % (static_cast<int32_t>(kPaletteMax) + 1);
    clear_custom_layers(veh);
    shv::call<void>(natives::SET_VEHICLE_COLOURS, veh, next, c2);
    thor_log("[cheat] next colour (61) -> ok c1=%d c2=%d", next, c2);
    return nullptr;
}

const char* run_swap_colours(int32_t ped, const snapshot::State& s) {
    int32_t veh = 0;
    if (const char* why = get_player_veh(ped, s, &veh)) return why;
    int32_t c1 = 0, c2 = 0;
    shv::call<void>(natives::GET_VEHICLE_COLOURS, veh, &c1, &c2);
    clear_custom_layers(veh);
    shv::call<void>(natives::SET_VEHICLE_COLOURS, veh, c2, c1);
    thor_log("[cheat] swap colours (62) -> ok c1=%d c2=%d", c2, c1);
    return nullptr;
}

const char* run_custom_colour(int32_t ped, const snapshot::State& s,
                              uint32_t packed) {
    int32_t veh = 0;
    if (const char* why = get_player_veh(ped, s, &veh)) return why;
    // Wire format: high byte = layer selector, low 24 bits = RGB.
    //   layer 0 = both layers (legacy swatch tap)
    //   layer 1 = primary layer only
    //   layer 2 = secondary layer only
    // Older companions always sent layer=0, so the "paint both" fallback
    // keeps every existing swatch tap doing what it did before.
    const uint32_t layer = (packed >> 24) & 0xFFu;
    const int32_t  r = static_cast<int32_t>((packed >> 16) & 0xFF);
    const int32_t  g = static_cast<int32_t>((packed >>  8) & 0xFF);
    const int32_t  b = static_cast<int32_t>( packed        & 0xFF);
    if (layer == 0 || layer == 1) {
        shv::call<void>(natives::SET_VEHICLE_CUSTOM_PRIMARY_COLOUR, veh, r, g, b);
    }
    if (layer == 0 || layer == 2) {
        shv::call<void>(natives::SET_VEHICLE_CUSTOM_SECONDARY_COLOUR, veh, r, g, b);
    }
    thor_log("[cheat] custom color (63) layer=%u rgb=#%02X%02X%02X -> ok",
             layer, r, g, b);
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

// CLEAR_AREA at the player's current coord with kClearAreaRadiusM
// radius. Flags from NativeTrainer's sample. Cleans up hostile peds
// and parked cars; does not touch the vehicle the player is in
// because the player's own entity is excluded by its handle.
void run_clear_area(int32_t ped) {
    const shv::Vec3 c = shv::call_vec3(natives::GET_ENTITY_COORDS, ped, 1);
    shv::call<void>(natives::CLEAR_AREA, c.x, c.y, c.z,
                    kClearAreaRadiusM, 1, 0, 0, 0);
}

// Teleport the player a few metres along the gameplay camera's
// forward yaw. Lets them clip through locked doors and short walls.
// Uses yaw only; pitch tilt is intentional ignored so the destination
// stays at camera height, matching NativeTrainer's "Teleport to
// Objective" style one-shot.
void run_portal(int32_t ped) {
    const shv::Vec3 cam = shv::call_vec3(natives::GET_GAMEPLAY_CAM_COORD);
    // rotation order 2 = EulerYXZ (yaw-pitch-roll). .z is the yaw in
    // degrees clockwise from north.
    const shv::Vec3 rot = shv::call_vec3(natives::GET_GAMEPLAY_CAM_ROT, 2);
    const float rad = rot.z * 3.14159265f / 180.0f;
    const float fx = -__builtin_sinf(rad);
    const float fy =  __builtin_cosf(rad);
    const float tx = cam.x + fx * kPortalForwardM;
    const float ty = cam.y + fy * kPortalForwardM;
    shv::call<void>(natives::SET_ENTITY_COORDS, ped, tx, ty, cam.z,
                    0, 0, 0, 1);
}

// Arm the async spawn state machine for a vehicle. Returns an err
// string on refusal, nullptr when the machine has been armed. The ack
// is posted from tick() when the machine resolves; the caller must
// not post one itself. Shared by the curated kSpawnCarFirst..Last ids,
// the random boat/plane/heli trainer ids, and (future) the vehicle
// library sheet.
const char* begin_spawn_vehicle_hash(uint32_t hash,
                                     uint32_t client, uint32_t cmd_id) {
    if (g_spawn.active) return kErrSpawnInProgress;
    if (!shv::call_bool(natives::IS_MODEL_IN_CDIMAGE, hash) ||
        !shv::call_bool(natives::IS_MODEL_A_VEHICLE, hash)) {
        return kErrModelBad;
    }
    shv::call<void>(natives::REQUEST_MODEL, hash);
    g_spawn.active = true;
    g_spawn.kind = kSpawnVehicle;
    g_spawn.model = hash;
    g_spawn.client = client;
    g_spawn.cmd_id = cmd_id;
    g_spawn.deadline_ms = GetTickCount64() + kSpawnTimeoutMs;
    thor_log("[cheat] spawn vehicle 0x%08X requested", hash);
    return nullptr;
}

// Convenience for the curated car ids 10..15. Translates the id to a
// model name, joaat's it, and hands off to the generic path.
const char* begin_spawn(uint32_t id, uint32_t client, uint32_t cmd_id) {
    const uint32_t slot = id - kSpawnCarFirst;
    if (slot >= kSpawnCarCount) return kErrUnknown;
    return begin_spawn_vehicle_hash(joaat(kSpawnCarModels[slot]), client, cmd_id);
}

// Arm the async spawn state machine for a ped (currently only the
// random-cop trainer id). Same deferred-ack contract as the vehicle
// path.
const char* begin_spawn_ped_hash(uint32_t hash,
                                 uint32_t client, uint32_t cmd_id) {
    if (g_spawn.active) return kErrSpawnInProgress;
    if (!shv::call_bool(natives::IS_MODEL_IN_CDIMAGE, hash)) {
        return kErrModelBad;
    }
    shv::call<void>(natives::REQUEST_MODEL, hash);
    g_spawn.active = true;
    g_spawn.kind = kSpawnPed;
    g_spawn.model = hash;
    g_spawn.client = client;
    g_spawn.cmd_id = cmd_id;
    g_spawn.deadline_ms = GetTickCount64() + kSpawnTimeoutMs;
    thor_log("[cheat] spawn ped 0x%08X requested", hash);
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
    // Loaded. Place in front of the ped so the entity does not land
    // on the player. Vehicle path: settle to ground and seat the ped.
    // Ped path: set heading, optionally arm, then release.
    const shv::Vec3 c = shv::call_vec3(natives::GET_ENTITY_COORDS, ped, 1);
    const float h = shv::call<float>(natives::GET_ENTITY_HEADING, ped);
    // Heading is degrees clockwise from north (y+). Forward unit:
    //   fx = -sin(h), fy = cos(h)
    const float rad = h * 3.14159265f / 180.0f;
    const float fx = -__builtin_sinf(rad), fy = __builtin_cosf(rad);
    const float sx = c.x + fx * kSpawnForwardM;
    const float sy = c.y + fy * kSpawnForwardM;
    if (g_spawn.kind == kSpawnVehicle) {
        const int32_t veh = shv::call<int32_t>(natives::CREATE_VEHICLE,
                                               g_spawn.model, sx, sy, c.z,
                                               h, 0, 0, 0);
        shv::call_bool(natives::SET_VEHICLE_ON_GROUND_PROPERLY, veh, 5.0f);
        shv::call<void>(natives::SET_PED_INTO_VEHICLE, ped, veh, -1);
        // Hand the model and the vehicle back to the game so it can
        // clean them up when the player drives away. SET_VEHICLE_AS_
        // NO_LONGER_NEEDED takes a Vehicle*; pass a local by address.
        int32_t veh_handle = veh;
        shv::call<void>(natives::SET_VEHICLE_AS_NO_LONGER_NEEDED, &veh_handle);
        shv::call<void>(natives::SET_MODEL_AS_NO_LONGER_NEEDED, g_spawn.model);
        thor_log("[cheat] spawn vehicle 0x%08X -> ok (veh=%d)",
                 g_spawn.model, veh);
    } else {
        const int32_t new_ped = shv::call<int32_t>(natives::CREATE_PED,
                                                   kPedTypeCop, g_spawn.model,
                                                   sx, sy, c.z, h, 0, 1);
        if (new_ped != 0) {
            // Arm the spawned cop with a pistol so a random-cop tap
            // feels distinct from a civilian; keep them hostile to
            // the player so they engage immediately.
            const uint32_t pistol = joaat("weapon_pistol");
            shv::call<void>(natives::GIVE_WEAPON_TO_PED, new_ped,
                            pistol, 500, 0, 1);
            shv::call<void>(natives::SET_PED_AS_ENEMY, new_ped, 1);
            int32_t ped_handle = new_ped;
            shv::call<void>(natives::SET_PED_AS_NO_LONGER_NEEDED, &ped_handle);
        }
        shv::call<void>(natives::SET_MODEL_AS_NO_LONGER_NEEDED, g_spawn.model);
        thor_log("[cheat] spawn ped 0x%08X -> ok (ped=%d)",
                 g_spawn.model, new_ped);
    }
    commands::post_ack(g_spawn.client, g_spawn.cmd_id, true, "");
    g_spawn = Spawn{};
}

}  // namespace

void run(uint32_t id, int32_t player, int32_t ped,
         const snapshot::State& s, uint32_t client, uint32_t cmd_id,
         uint32_t arg) {
    if (ped == 0) {
        commands::post_ack(client, cmd_id, false, kErrNoPed);
        return;
    }
    if (id == 0) {
        commands::post_ack(client, cmd_id, false, kErrUnknown);
        return;
    }
    // Paint + trainer one-shots live past the toggle range;
    // whitelisted directly so they go through the one-shot path
    // below. Every other id past kMaxToggle is still unknown.
    const bool is_paint_id   = (id >= kRespray && id <= kCustomColour);
    const bool is_trainer_id = (id >= kClearArea && id <= kPortal);
    if (id > kMaxToggle && !is_paint_id && !is_trainer_id) {
        commands::post_ack(client, cmd_id, false, kErrUnknown);
        return;
    }

    // Toggles.
    if (id >= kFirstToggle && id <= kMaxToggle) {
        if (!v_knows_toggle(id)) {
            commands::post_ack(client, cmd_id, false, kErrNotInBuild);
            return;
        }
        mark_session_cheated();
        set_toggle(id, !is_on(id));
        apply_toggle(id, player, ped, &s);
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

    // Trainer random-spawn one-shots. Same deferred-ack contract as
    // the curated car ids: ack posts from advance_spawn() when the
    // model finishes loading, or from begin_* on refusal.
    if (id == kRandomCop || id == kRandomBoat
            || id == kRandomPlane || id == kRandomHeli) {
        const char* why = nullptr;
        if (id == kRandomCop) {
            why = begin_spawn_ped_hash(
                pick_random(kCopPedModels, kCopPedCount), client, cmd_id);
        } else if (id == kRandomBoat) {
            why = begin_spawn_vehicle_hash(
                pick_random(kRandomBoatModels, kRandomBoatCount), client, cmd_id);
        } else if (id == kRandomPlane) {
            why = begin_spawn_vehicle_hash(
                pick_random(kRandomPlaneModels, kRandomPlaneCount), client, cmd_id);
        } else {
            why = begin_spawn_vehicle_hash(
                pick_random(kRandomHeliModels, kRandomHeliCount), client, cmd_id);
        }
        if (why) {
            thor_log("[cheat] id=%u (random spawn) -> %s", id, why);
            commands::post_ack(client, cmd_id, false, why);
        } else {
            mark_session_cheated();
        }
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
    case kWantedDown:    run_wanted_down(player); break;
    case kAllWeapons:    run_all_weapons(ped); break;
    case kMaxAmmo:       run_max_ammo(ped); break;
    case kFixVehicle:    err = run_fix_vehicle(ped, s); break;
    case kNextWeather:   run_next_weather(); break;
    case kNextTimeOfDay: run_next_time(); break;
    case kFillSpecial:   err = run_fill_special(player, s); break;
    case kGiveParachute: run_give_parachute(ped); break;
    case kSkyfall:       run_skyfall(ped); break;
    case kRespray:       err = run_respray(ped, s); break;
    case kNextColour:    err = run_next_colour(ped, s); break;
    case kSwapColours:   err = run_swap_colours(ped, s); break;
    case kCustomColour:  err = run_custom_colour(ped, s, arg); break;
    case kClearArea:     run_clear_area(ped); break;
    case kPortal:        run_portal(ped); break;
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
    // because the game resets them on some transitions. "This frame"
    // toggles need to fire every rendered frame; the script fiber
    // compensates by switching to script_wait(0) when needs_frame_wait().
    //
    // tick() does not know the full snapshot state (it would be stale
    // if a toggle re-applied against last frame's values), so slippery
    // vehicles — which needs s.in_vehicle — gets a lightweight live
    // query via IS_PED_IN_ANY_VEHICLE.
    if (g_mask != 0) {
        snapshot::State live{};
        bool queried_vehicle = false;
        for (uint32_t id = kFirstToggle; id <= kMaxToggle; ++id) {
            if (!is_on(id) || !v_knows_toggle(id)) continue;
            // Both vehicle-targeted toggles need the live in_vehicle
            // flag; shared one-shot query so a tick with both bits
            // lit only calls IS_PED_IN_ANY_VEHICLE once.
            if ((id == kSlipperyVehicles || id == kInvincibleVehicle)
                    && !queried_vehicle) {
                live.in_vehicle = shv::call_bool(natives::IS_PED_IN_ANY_VEHICLE,
                                                 ped, 0);
                queried_vehicle = true;
            }
            apply_toggle(id, player, ped, &live);
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

bool needs_frame_wait() {
    return (g_mask & kThisFrameToggleMask) != 0;
}

void reset() {
    g_mask = 0;
    g_weather_idx = 0;
    g_stat_marked = false;
    g_spawn = Spawn{};
}

}  // namespace cheats
