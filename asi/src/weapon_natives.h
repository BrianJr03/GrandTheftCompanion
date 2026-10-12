#pragma once

#include <cstdint>

// game:: — the three script natives the weapon-wheel code (weapons.cpp)
// needs, as plain typed functions.
//
// They are split out from weapons.cpp so that file holds only logic and
// can be compiled and tested on any host with fakes for these (see
// asi/test/). The real definitions in weapon_natives.cpp are a few
// lines each of natives::Context marshalling; like every native call
// they must run on the game thread while natives::ok_to_invoke().
//
// Signatures follow FusionFix source/natives.ixx and iv-sdk
// Scripting/BaseScripting.h, which agree with each other.

namespace game {

// GET_CHAR_WEAPON_IN_SLOT(Ped, uint slot, uint* weapon, uint* ammo0,
// uint* ammo1). All three out-pointers are written by the native and
// must be valid. `slot` must be a real slot of the ped's weapon array
// (see weapons::kPedSlots); the native does not range-check it.
void get_char_weapon_in_slot(uint32_t ped, uint32_t slot, uint32_t* weapon,
                             uint32_t* ammo0, uint32_t* ammo1);

// GET_AMMO_IN_CHAR_WEAPON(Ped, uint weapon, uint* ammo). Never pass
// weapon 0: probes.cpp records that as crashing the game.
uint32_t get_ammo_in_char_weapon(uint32_t ped, uint32_t weapon);

// IS_BUTTON_PRESSED(uint padIndex, uint button) -> bool. Reads the pad
// itself, not a game action, so it answers regardless of what the
// button is bound to and whether the player currently has control.
bool is_button_pressed(uint32_t pad, uint32_t button);

}  // namespace game
