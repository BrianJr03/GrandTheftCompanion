#pragma once

#include <cstdint>

#include "snapshot.h"

// wardrobe:: — change-outfit page on the companion. The companion
// asks for the current ped's catalogue with `wardrobe_list`, the
// plugin walks the component slots (0..11) and prop slots (0, 1, 2,
// 6, 7) in small slices via the GET_NUMBER_OF_PED_* natives,
// publishes a Wardrobe frame, and applies one of four commands when
// the user taps something.
//
// Component slots and prop slots share the same u32 wire packing
// (see bridge.h "Wardrobe"). Prop drawable 0xFFF means "nothing
// fitted", which maps to CLEAR_PED_PROP on the game side so a prop
// slot can empty out (hat off, glasses off).
//
// The catalogue walk is driven from wardrobe::tick() on the script
// fiber, gated on there being at least one client that has asked for
// it and on a valid player ped. A ped-model change (character
// switch, Director Mode skin, animal form) drops the old walk and
// re-reads against the new ped.

namespace wardrobe {

// How many of the ~17 slots the walker reads per tick. One tick is
// 25 Hz; four keeps the catalogue arriving in ~200 ms on a protagonist
// while leaving most of the native budget to the HUD. Clothing slots
// are heavier than vehicle-mod slots because each drawable has its
// own GET_NUMBER_OF_PED_TEXTURE_VARIATIONS call.
constexpr uint32_t kSlotsPerTick = 4;

// Component slot ids 0..11 (NativeDB enum). The walker hides
// kHiddenComponents by design: 0 is the head (face), 10 is decals;
// changing either reads as a face edit rather than a wardrobe
// action and lives behind the standalone character creator in the
// real game.
constexpr uint8_t kFirstComponent = 0;
constexpr uint8_t kLastComponent  = 11;

// Prop slots the real game uses: 0 hat, 1 glasses, 2 ears, 6 watch,
// 7 bracelet. Slots 3..5 are never populated on SP protagonists.
// kClearProp is the "nothing fitted" sentinel on the wire — the
// plugin unpacks it to -1 which CLEAR_PED_PROP interprets as "off".
constexpr uint16_t kClearProp = 0xFFF;

// A client asked for the current ped's catalogue. If a walk is not
// already in flight the next tick starts one; if the current
// catalogue is already fresh for this ped, publishes it again (with
// a bumped version) so the client sees new data without waiting for
// the next ped change.
void request();

// Script fiber: advance the catalogue walker a slice. `ped` is this
// frame's player ped (0 while loading / cutscene). Called every
// tick, cheap when there is no outstanding request and the ped has
// not changed.
void tick(int32_t ped);

// Ped model just changed (character switch, Director Mode, animal
// form). Drops the in-flight walk and clears the published
// catalogue so the client sees "empty" rather than the previous
// ped's slots until the new walk resolves.
void on_ped_changed();

// Script fiber: apply one command. Returns false (with `err` filled
// in) when the command is refused — ped missing, bad piece, bad
// part within an outfit, in-vehicle, etc. The caller (commands::
// drain) posts the ack and does not touch the native side itself.
//
// `name` is one of:
//   wardrobe_list, set_piece, apply_outfit, reset_outfit.
// `arg0` carries the one-u32 packed piece for set_piece; `parts` /
// `n_parts` carries the up-to-24 packed pieces for apply_outfit;
// every other command ignores both.
bool apply(const char* name, uint32_t arg0,
           const uint32_t* parts, uint32_t n_parts,
           int32_t ped, bool in_vehicle, char* err, size_t cap);

// For tests / crash-recovery: forget every bit of wardrobe state.
void reset();

}  // namespace wardrobe
