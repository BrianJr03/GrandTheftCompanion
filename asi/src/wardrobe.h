#pragma once

#include <cstdint>

#include "snapshot.h"

// wardrobe:: — change-outfit page on the companion (GTA IV side).
// The companion asks for the current char's catalogue with
// wardrobe_list; the plugin walks the component slots (0..10, minus
// a hidden set) and the prop slots (0 hat, 1 glasses) a few at a
// time via the GET_NUMBER_OF_CHAR_* natives, publishes a Wardrobe
// frame, and applies one of four commands when the user taps.
//
// Mirrors asi-v/src/wardrobe.h's shape so the companion-side screen
// is a straight port. Two IV-specific wrinkles:
//   * no palette index (IV's SET_CHAR_COMPONENT_VARIATION takes
//     drawable + texture only);
//   * no per-prop texture-count native — the walker publishes
//     colours = 0 for prop rows, and apply() lets the companion
//     write a new texture via SET_CHAR_PROP_INDEX_TEXTURE then
//     reads back whether it stuck.
//
// The .cpp is Windows-free: every native call routes through
// asi/src/cheat_natives.h's typed wrappers so this file can be
// unit-tested on any host by linking a fake game.

namespace wardrobe {

// Slots walked per tick. One IV tick is ~25 Hz; four keeps the
// catalogue arriving in ~200 ms on a protagonist. Clothing slots
// are heavier than mod slots because each drawable has its own
// GET_NUMBER_OF_CHAR_TEXTURE_VARIATIONS call.
constexpr uint32_t kSlotsPerTick = 4;

// Prop sentinel ("nothing fitted"). The wire layer uses 0xFFF for a
// prop clear; this file translates to a CLEAR_CHAR_PROP call.
constexpr uint16_t kClearProp = 0xFFF;

// A client asked for the current ped's catalogue. The next tick
// starts the walk if one is not already in flight; if the catalogue
// is already fresh for this ped the walker republishes it (with a
// bumped version) so the client sees new data at once.
void request();

// Game thread: advance the catalogue walker a slice. `ped` is this
// frame's player ped handle (0 while out of play). Cheap when there
// is no outstanding request and the ped has not changed.
void tick(uint32_t ped, int32_t episode);

// Ped model just changed (character switch is a V-ism; IV reaches
// this via session reset / loading). Drops the in-flight walk and
// clears the published catalogue.
void on_ped_changed();

// Game thread: apply one command. Returns false (with err filled in)
// when refused — in a vehicle, missing ped, bad piece, bad part N,
// etc. The caller (commands::drain) posts the ack and does not touch
// the game side itself.
//
// `name` is one of:
//   wardrobe_list, set_piece, apply_outfit, reset_outfit.
// `arg0` carries one packed u32 for set_piece; `parts` / `n_parts`
// carries up to kMaxParts packed pieces for apply_outfit; every
// other command ignores both.
bool apply(const char* name, uint32_t arg0,
           const uint32_t* parts, uint32_t n_parts,
           uint32_t ped, bool in_vehicle, int32_t episode,
           char* err, size_t cap);

// Tests / crash-recovery: forget every bit of wardrobe state.
void reset();

}  // namespace wardrobe
