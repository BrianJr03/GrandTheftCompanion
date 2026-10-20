#pragma once

#include <cstdint>

#include "snapshot.h"

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
    kSpawnCar0         = 10,  // V only (ids 10..15 are the curated models)
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
};

// Toggle id range. Toggle id N lights bit (N - kFirstToggle) in the
// mask that cheats::mask() returns and that the state frame carries.
constexpr uint32_t kFirstToggle = 20;
constexpr uint32_t kMaxToggle   = 31;

// Known one-shot id ranges. The companion's catalog grows across
// phases; the plugin keeps a loose cap so new ids are refused as
// "unknown cheat" rather than silently routed as toggles.
//   [1, 19]   — scalar one-shots and spawn-car ids (spawn-car is V-only)
//   [40, 59]  — second-wave scalar one-shots (IV weapon sets, future
//               vehicle-spawn pass)
constexpr uint32_t kMaxLowOneShot    = 19;
constexpr uint32_t kFirstHighOneShot = 40;
constexpr uint32_t kMaxHighOneShot   = 59;

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

// Default ammo given per weapon by "all weapons" + "max ammo". IV's
// game cheats grant large but finite ammo; 9999 is the common choice.
constexpr uint32_t kFullAmmo = 9999;

// Reasons run() refuses a request. Short strings so the ack frame
// does not need to escape anything. All reused by the host tests to
// pin the exact wording.
extern const char* const kErrUnknown;
extern const char* const kErrNotInBuild;
extern const char* const kErrNoPed;
extern const char* const kErrNotInVehicle;

// Dispatch one tap. Returns nullptr on ok, a short reason string on
// refusal. For toggles, flips the mask bit then runs the native with
// the new state. The caller supplies the resolved player id and ped
// handle (from the frame's identity natives) and the already-filled
// snapshot (for `in_vehicle`, `owned_weapons`, etc.).
const char* run(uint32_t id, uint32_t player, uint32_t ped,
                const snapshot::Player& p);

// Re-apply active toggles when the player ped changed since the last
// tick. Cheap for an empty mask; no-op when ped == 0.
void tick(uint32_t player, uint32_t ped);

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
