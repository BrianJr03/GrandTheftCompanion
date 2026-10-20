#pragma once

#include <cstdint>

#include "snapshot.h"

// cheats:: — GTA V side of the companion's cheats tab. One command id
// in (one of CheatId below), one effect out: health, armour, wanted,
// loadout, weather, clock, world toggles and curated-model spawns.
//
// This file is the mirror of asi/src/cheats.h: same ids, same mask bit
// layout, same wire format. The V implementation re-applies active
// toggles every tick (character switches swap the ped, and world
// toggles like slow motion are reset by some game transitions).
//
// Reasoning about safety:
//   * Every cheat runs on the script fiber from commands::drain or
//     cheats::tick, so there is no cross-thread call into Script Hook
//     V — the only safe context for a native.
//   * The command handler calls blocked_reason() first (online,
//     not-in-game, switching, cutscene), so run() can assume a live
//     player and ped.
//   * Spawning a vehicle is multi-tick: REQUEST_MODEL -> wait up to
//     5 s for HAS_MODEL_LOADED -> CREATE_VEHICLE -> seat the ped ->
//     release the model and the vehicle. One spawn is in flight at a
//     time; a second tap while one is pending is refused.

namespace cheats {

// Ids shared with asi/src/cheats.h and the app's catalog. Keep in sync.
enum CheatId : uint32_t {
    kHeal              = 1,
    kWantedUp          = 2,
    kWantedClear       = 3,
    kAllWeapons        = 4,
    kMaxAmmo           = 5,
    kFixVehicle        = 6,
    kNextWeather       = 7,
    kNextTimeOfDay     = 8,
    kFillSpecial       = 9,
    kSpawnCarFirst     = 10,  // ids 10..15 (adder, zentorno, bati, sanchez, frogger, bmx)
    kSpawnCarLast      = 15,
    kWantedDown        = 16,  // -1 star (vs kWantedClear which zeroes)
    kGiveParachute     = 17,
    kSkyfall           = 18,
    kInvincible        = 20,
    kInfiniteClip      = 21,
    kFastReload        = 22,  // IV-only; refused here
    kNeverTired        = 23,  // IV-only; refused here
    kFastRun           = 24,
    kLowGravity        = 25,
    kSlowMotion        = 26,
    kPoliceIgnore      = 27,
    kDrunkMode         = 28,
    kFastSwim          = 29,
    kSuperJump         = 30,  // this-frame
    kExplosiveAmmo     = 31,  // this-frame
    kExplosiveMelee    = 32,  // this-frame
    kIncendiaryAmmo    = 33,  // this-frame
    kSlipperyVehicles  = 34,
};

constexpr uint32_t kFirstToggle = 20;
constexpr uint32_t kMaxToggle   = 34;

// Bitmask of toggle ids whose natives have to be re-called every
// game frame (the "SET_*_THIS_FRAME" family). When any of these is on,
// script::main() switches the fiber to script_wait(0) so the game
// resumes it every frame instead of every 40 ms.
constexpr uint32_t kThisFrameToggleMask =
    (1u << (kSuperJump      - kFirstToggle)) |
    (1u << (kExplosiveAmmo  - kFirstToggle)) |
    (1u << (kExplosiveMelee - kFirstToggle)) |
    (1u << (kIncendiaryAmmo - kFirstToggle));

// Spawn-car curated model names (joaat'd at use-time via cheats.cpp).
// One entry per id in [kSpawnCarFirst, kSpawnCarLast]. Models that the
// running game build does not have (DLC removed, Enhanced-only, etc.)
// fail IS_MODEL_IN_CDIMAGE and the tap is refused.
constexpr uint32_t kSpawnCarCount = kSpawnCarLast - kSpawnCarFirst + 1;
extern const char* const kSpawnCarModels[kSpawnCarCount];

// Weather and clock cycles.
constexpr uint32_t kWeatherCount = 9;
extern const char* const kWeatherNames[kWeatherCount];
constexpr uint32_t kClockHourCount = 4;
extern const uint32_t kClockHours[kClockHourCount];

// Toggle values.
constexpr float    kSlowTimeScale   = 0.5f;
constexpr float    kFastRunMult     = 1.49f;  // NativeDB: values above
                                              // 1.49 are ignored
constexpr float    kFastSwimMult    = 1.49f;  // same cap as sprint
constexpr int32_t  kLowGravityLevel = 2;      // 0 full, 3 moonwalk
constexpr int32_t  kFullAmmo        = 9999;
constexpr float    kFullBodyHealth  = 1000.0f;
constexpr float    kSpawnForwardM   = 3.0f;   // offset in front of the
                                              // ped so the car does not
                                              // land on them
constexpr float    kSkyfallZ        = 1000.0f; // altitude for free-fall

constexpr uint32_t kSpawnTimeoutMs = 5000;

// Short error strings reused by the host-visible ack path.
extern const char* const kErrUnknown;
extern const char* const kErrNotInBuild;
extern const char* const kErrNoPed;
extern const char* const kErrNotInVehicle;
extern const char* const kErrSpecialLocked;
extern const char* const kErrSpawnInProgress;
extern const char* const kErrModelBad;
extern const char* const kErrSpawnTimeout;

// Dispatch one tap. Posts the ack (via commands::post_ack) itself for
// synchronous cheats; for spawn-car ids it kicks off the state machine
// and defers the ack until the next cheats::tick resolves it.
void run(uint32_t id, int32_t player, int32_t ped,
         const snapshot::State& s, uint32_t client, uint32_t cmd_id);

// Called every tick on the script fiber, after commands::drain. Does
// three things: re-applies active toggles (per-ped ones use the
// current ped so a character switch does not clear them, world ones
// re-apply because the game resets them on some transitions),
// advances the spawn state machine, and posts a deferred ack when
// the spawn resolves.
void tick(int32_t player, int32_t ped);

// Called when the script tick decides this frame is not in-game
// (online, loading screen, no player ped). Writes TIME_SCALE=1.0,
// CLEAR_WEATHER_TYPE_PERSIST, SET_GRAVITY_LEVEL(0) so the menu runs
// at normal speed. The mask is kept; cheats::tick re-applies toggles
// when in-game resumes.
void reset_world();

// Current toggle mask: for the state frame.
uint32_t mask();

// True when any "this frame" toggle is on. The script fiber uses
// this to decide between script_wait(40 ms) and script_wait(0).
bool needs_frame_wait();

// Forget everything remembered between frames. For tests.
void reset();

}  // namespace cheats
