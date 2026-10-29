#pragma once

#include <cstdint>

#include "snapshot.h"

// Opaque forward decl so cheats.h stays free of the commands.h
// dependency. cheats.cpp includes commands.h for the real type.
namespace commands { class AckQueue; }

// cheats:: — GTA IV side of the companion's cheats tab. Dispatches
// one id to one effect through game:: natives (see cheat_natives.h),
// keeps a toggle bitmask the state frame publishes, and re-applies
// active toggles when the player ped changes.
//
// Logic only: no <windows.h>, no game reach outside cheat_natives.h,
// no clock — the clock is passed in. All callable from the host-side
// tests under asi/test/cheats_test.cpp with fakes for every game::*.
//
// Everything here runs on the game thread, from probes::on_frame
// (cheats::tick) and commands::drain (cheats::run), both inside the
// natives::ok_to_invoke() window. The host tests do not model that
// gate; the real wrappers forward through natives::invoke which does.

namespace cheats {

// --- ids (shared with the GTA V plugin and the app's catalog) ----
// Keep in sync with app/.../ui/cheats/CheatCatalog.kt. The GTA IV
// plugin runs the subset marked (IV) below; the rest are V-only and
// answer with "not in this build" so the app can still send them.
enum CheatId : uint32_t {
    kHeal              = 1,   // IV + V
    kWantedUp          = 2,   // IV + V
    kWantedClear       = 3,   // IV + V
    kAllWeapons        = 4,   // IV + V
    kMaxAmmo           = 5,   // IV + V
    kFixVehicle        = 6,   // IV + V
    kNextWeather       = 7,   // IV + V
    kNextTimeOfDay     = 8,   // IV + V
    kFillSpecial       = 9,   // V only (no GTA IV special meter)
    kSpawnCar0         = 10,  // V only (ids 10..15 are the curated V models)
    kSpawnCar5         = 15,
    kGiveParachute     = 17,  // IV + V (IV: WEAPON_PARACHUTE)
    kAdvancedWeapons   = 19,  // IV phone cheat 486-555-0100
    kInvincible        = 20,  // IV + V (toggle)
    kInfiniteClip      = 21,  // V only (toggle)
    kFastReload        = 22,  // IV only (toggle)
    kNeverTired        = 23,  // IV only (toggle)
    kFastRun           = 24,  // V only (toggle)
    kLowGravity        = 25,  // V only (toggle)
    kSlowMotion        = 26,  // IV + V (toggle)
    kPoliceIgnore      = 27,  // IV + V (toggle)
    kPoorWeapons       = 40,  // IV phone cheat 486-555-0150
    kUltimate          = 41,  // IV phone cheat 482-555-0100
                              // (heal + armor + ammo + repair + advanced)

    // Base-IV phone-cheat vehicles (227-555-xxxx, 625-555-xxxx,
    // 359-555-0100, 938-555-0100). Available in every episode of
    // Complete Edition — the vanilla phone cheats always work.
    kSpawnIvFirst      = 50,  // ids 50..58 (FIB Buffalo..Turismo)
    kSpawnIvLast       = 58,

    // Paint. Shared with asi-v/src/cheats.h; GTA IV runs 60..62 and
    // refuses 63 (RGB) with kErrNotInGame — the custom-colour natives
    // are GTA V only. Reachable only via the id-shaped cheat command
    // for 60..62; id 63 is sent by the companion's paint_rgb command
    // but IV never advertises paint_rgb support.
    kRespray           = 60,
    kNextColour        = 61,
    kSwapColours       = 62,
    kCustomColour      = 63,  // refused on IV
};

// Toggle id range. Toggle id N lights bit (N - kFirstToggle) in the
// mask that cheats::mask() returns and that the state frame carries.
constexpr uint32_t kFirstToggle = 20;
constexpr uint32_t kMaxToggle   = 31;

// Known one-shot id ranges. The companion's catalog grows across
// phases; the plugin keeps a loose cap so new ids are refused as
// "unknown cheat" rather than silently routed as toggles.
//   [1, 19]   — scalar one-shots and spawn-car ids (spawn-car is V-only)
//   [40, 79]  — IV weapon sets (40..41) + IV vehicle spawns (50..58);
//               leaves headroom for TLAD/TBoGT episode-only spawns
//               (60..65 and 70..79) in a follow-up.
constexpr uint32_t kMaxLowOneShot    = 19;
constexpr uint32_t kFirstHighOneShot = 40;
constexpr uint32_t kMaxHighOneShot   = 79;

// Max player health (IV reports 100..200 for the player ped) and armour.
constexpr uint32_t kMaxHealth = 200;
constexpr uint32_t kMaxArmour = 100;

// Weather and clock cycles. Advancing past the last weather entry calls
// RELEASE_WEATHER instead of forcing a weather id (hand control back to
// the game); the cycle index then wraps to 0 on the next tap. Clock
// jumps to whichever of these hours is next.
constexpr uint32_t kWeatherCount = 10;
extern const uint32_t kWeatherIds[kWeatherCount];
constexpr uint32_t kClockHourCount = 4;
extern const uint32_t kClockHours[kClockHourCount];

// Weapon ids given by the "all weapons" cheat. Base-IV ids 1..20
// minus id 8 (empty slot in the eWeaponType enum). Id 0 (unarmed) is
// the "no weapon" sentinel and never offered. Episode-DLC ids past
// 20 are only safe when GET_CURRENT_EPISODE is right; left out of v1
// (see the task spec).
constexpr uint32_t kAllWeaponsCount = 19;
extern const uint32_t kAllWeaponsIds[kAllWeaponsCount];

// Phone-code weapon sets (486-555-0100 and 486-555-0150). Specific
// loadouts the game grants when you dial those cheats — different
// from the companion's catch-all "all weapons".
constexpr uint32_t kAdvancedWeaponsCount = 8;
extern const uint32_t kAdvancedWeaponsIds[kAdvancedWeaponsCount];
constexpr uint32_t kPoorWeaponsCount = 8;
extern const uint32_t kPoorWeaponsIds[kPoorWeaponsCount];

// WEAPON_PARACHUTE in the IV eWeaponType enum (slot 11, between the
// gift and nothing else above it). Given on tap of the parachute cheat.
constexpr uint32_t kWeaponParachute = 25;

// Base-IV phone-cheat vehicle models. One entry per id in
// [kSpawnIvFirst, kSpawnIvLast]. Names are what the game registers
// in its model-info-store. An unknown name just makes the spawn
// time out — the hash comes from GET_HASH_KEY at run-time, so a
// wrong name cannot land on an unrelated native.
constexpr uint32_t kSpawnIvCount = kSpawnIvLast - kSpawnIvFirst + 1;
extern const char* const kSpawnIvModels[kSpawnIvCount];

// How long the spawn state machine polls HAS_MODEL_LOADED before
// giving up (same budget as the GTA V plugin uses).
constexpr uint32_t kSpawnTimeoutMs = 5000;
// Offset in front of the ped so the spawned car does not land on
// the player.
constexpr float    kSpawnForwardM  = 3.0f;

// Spawn-specific errors.
extern const char* const kErrSpawnInProgress;
extern const char* const kErrModelBad;
extern const char* const kErrSpawnTimeout;

// Default ammo given per weapon by "all weapons" + "max ammo". IV's
// game cheats grant large but finite ammo; 9999 is the common choice.
constexpr uint32_t kFullAmmo = 9999;

// RPG (weapon id 18 in base IV's eWeaponType). Max-ammo tops this up
// to a usable-but-finite reserve instead of kFullAmmo — 9999 rockets
// trivialises every mission that is not about the RPG.
constexpr uint32_t kWeaponRpg   = 18;
constexpr uint32_t kRpgMaxAmmo  = 8;

// Reasons run() refuses a request. Short strings so the ack frame
// does not need to escape anything. All reused by the host tests to
// pin the exact wording.
extern const char* const kErrUnknown;
extern const char* const kErrNotInBuild;
extern const char* const kErrNoPed;
extern const char* const kErrNotInVehicle;
// Paint: id 63 (RGB) is only in the GTA V plugin. The IV side keeps
// the id and the wrapper but answers with this exact string so the
// companion can tell "feature not here" from "feature refused this
// time".
extern const char* const kErrNotInGame;

// Palette cap for respray / next colour / swap. Prediction from the
// stock carcols.dat; refined on-device in Gate IV1. Writes past the
// cap are clamped or ignored by the game, not fatal.
constexpr uint32_t kPaletteMax = 133;

// Dispatch one synchronous tap. Returns nullptr on ok, a short reason
// string on refusal. For toggles, flips the mask bit then runs the
// native with the new state. The caller supplies the resolved player
// id and ped handle (from the frame's identity natives) and the
// already-filled snapshot (for `in_vehicle`, `owned_weapons`, etc.).
//
// Spawn-car ids (kSpawnIvFirst..kSpawnIvLast) are NOT handled here;
// see begin_spawn.
const char* run(uint32_t id, uint32_t player, uint32_t ped,
                const snapshot::Player& p);

// Arm the vehicle-spawn state machine. Returns nullptr when the
// machine was armed (ack is deferred: cheats::tick will post it when
// the model loads and the car is created), or an err string when the
// state machine refuses (spawn already in progress, id out of range,
// GET_HASH_KEY returned 0). `now_ms` is the caller's GetTickCount
// reading (passed in so this file stays host-testable).
const char* begin_spawn(uint32_t id, uint32_t player, uint32_t ped,
                        commands::AckQueue* ack_queue, uint32_t cmd_id,
                        uint32_t now_ms);

// Re-apply active toggles when the player ped changed since the last
// tick, and advance the vehicle-spawn state machine if one is in
// flight (posts the deferred ack when the car is under the player
// or the timeout is hit). Cheap for an empty mask and no spawn.
// `now_ms` is the caller's GetTickCount reading.
void tick(uint32_t player, uint32_t ped, uint32_t now_ms);

// World-level toggles the game resets on its own transitions. Called
// from probes when in_game drops back to false (quit to menu, loading)
// so the menu runs at normal time scale and under game weather. Mask
// is kept, so re-entering in-game re-applies the toggles via tick().
void reset_world();

// Current toggle mask: for the state frame.
uint32_t mask();

// Forget everything remembered between frames. For tests.
void reset();

}  // namespace cheats
