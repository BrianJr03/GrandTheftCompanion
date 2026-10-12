#pragma once

#include <cstdint>

#include "snapshot.h"

// weapons:: — what the companion's weapon wheel needs from GTA IV: the
// weapons the player carries, and whether LB / L1 is being held.
//
// GTA IV has no weapon wheel of its own. The companion draws one, and
// shows it for as long as LB is held (or after a tap on its weapon
// card); a tapped weapon arrives as the `set_weapon` command.
//
// This file and weapons.cpp are logic only: no <windows.h>, the clock is
// passed in, and the game is reached through weapon_natives.h. That is
// what lets asi/test/ run them on the build machine.
//
// Everything here runs on the game thread, from probes.cpp's on_frame
// (natives level 4) and from commands::drain.

namespace weapons {

// The ped's weapon array has 12 entries in Complete Edition 1.2.0.59
// (iv-sdk CPed.h: PedWeapons::m_aWeapons[12]), i.e. slots 0..11, and
// GET_CHAR_WEAPON_IN_SLOT indexes it unchecked. The first attempt at
// this scan walked all 13 names of the scripting eWeaponSlot enum and
// crashed the game; slot 12 is past the end of the array.
constexpr uint32_t kPedSlots = 12;

// Slots the scan reads: melee, handgun, shotgun, SMG, rifle, sniper,
// heavy, thrown, special. Slot 0 is always fists. Slots 10 (gift) and
// 11 (parachute) are not weapons the wheel offers, so they are left
// alone rather than read for nothing.
constexpr uint32_t kFirstSlot = 1;
constexpr uint32_t kLastSlot  = 9;

// Weapon ids are small; anything at or above this is a transient or
// garbage read. Same bound probes.cpp uses for the current weapon.
constexpr uint32_t kMaxWeaponId = 200;
constexpr uint32_t kUnarmed = 0;

// The carried weapons change rarely. Rescan this often, and at once
// when LB goes down or the player ped changes.
constexpr uint32_t kScanMs = 1000;

// LB has to stay down this long before `wheel` is reported. On foot a
// tap of LB is the game's pick-up button; that must not flash the
// companion's wheel. Tune on-device.
constexpr uint32_t kWheelHoldMs = 150;

// IS_BUTTON_PRESSED arguments (iv-sdk eButton; FusionFix polls the same
// pair for its turn indicators).
constexpr uint32_t kPad      = 0;
constexpr uint32_t kButtonLB = 4;

// Set p.wheel. Needs p.in_game and p.in_vehicle already filled in. On
// foot only: in a vehicle LB belongs to the game.
void read_wheel(uint32_t now_ms, snapshot::Player& p);

// Fill p.owned_weapons / p.owned_ammo (indexed by game slot) from the
// last scan, rescanning first if it is due, and set p.owned_valid.
void read_owned(uint32_t ped, uint32_t now_ms, snapshot::Player& p);

// Why `set_weapon` must be refused for this weapon id, or nullptr if it
// may run. Ownership is only checked when p.owned_valid.
const char* set_weapon_error(const snapshot::Player& p, uint32_t weapon);

// Forget everything remembered between frames. For tests.
void reset();

}  // namespace weapons
