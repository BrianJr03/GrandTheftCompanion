#pragma once

#include <cstdint>

#include "snapshot.h"

// mods:: — Los Santos Customs on the companion. The companion asks for
// the current vehicle's catalogue with `mods_list`, the plugin walks
// slots 0..48 with GET_NUM_VEHICLE_MODS and GET_MOD_TEXT_LABEL in
// small slices (so one tick does not freeze the game), publishes a
// Mods frame, and then applies one of eleven commands when the user
// taps something.
//
// Three slots are three-state toggles instead of index lists: 18
// (turbo), 20 (tyre smoke), 22 (xenon). The plugin publishes them in
// a separate `toggles` list so the app can render them as a switch.
//
// The catalogue walk is driven from mods::tick() on the script fiber,
// gated on in-vehicle and on there being at least one client that
// has asked for it. Changing wheel type rebuilds slot 23 (and 24 on
// bikes) because the option count is per wheel type.

namespace mods {

// How many of the 49 modType ids the walker reads per tick. One
// tick is 25 Hz; 8 keeps the catalogue arriving in ~250 ms on a
// stock car while leaving most of the native budget to the HUD.
constexpr uint32_t kSlotsPerTick = 8;

// A client asked for the current vehicle's catalogue. If a walk is
// not already in flight the next tick starts one; if the current
// catalogue is already fresh for this vehicle, publishes it again
// (with a bumped version) so the client sees new data without
// waiting for the next vehicle change.
void request();

// Script fiber: advance the catalogue walker a slice. `veh` is the
// vehicle the player is currently in (0 = on foot). Called every
// tick, cheap when there is no outstanding request and the vehicle
// has not changed.
void tick(int32_t veh);

// Vehicle handle just changed. Drops the in-flight walk and clears
// the published catalogue so the client sees "empty" rather than
// the previous car's slots until the new walk resolves.
void on_vehicle_changed();

// Script fiber: apply one command. Returns false (with `err` filled
// in) when the command is refused — out of range, no mod kit on this
// car, bad plate text, etc. The caller (commands::drain) posts the
// ack and does not touch the native side itself.
//
// `name` is one of:
//   set_mod, toggle_mod, set_wheel_type, set_tint, set_plate_style,
//   set_plate, set_neon, set_smoke, set_xenon_colour,
//   set_bulletproof_tyres, mods_list.
// `arg` carries the single u32 from the wire; plate text is passed
// in `text` (empty for every other command).
bool apply(const char* name, uint32_t arg, const char* text,
           int32_t veh, char* err, size_t cap);

// For tests / crash-recovery: forget every bit of mods state.
void reset();

}  // namespace mods
