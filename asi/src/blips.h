#pragma once

#include <cstdint>

#include "snapshot.h"

// blips:: — the map's blips and the GPS route, GTA IV edition.
//
// Why: the companion wants to route the player to the next mission
// without the pause map. GTA IV has no native that sets the player's
// waypoint, but SET_ROUTE draws the game's GPS line to any existing
// blip, and mission starts are blips. So the plugin walks the map's
// blips (a census: raw sprite ids and all), publishes the nearest as a
// versioned list, and on request turns the route on for one of them.
//
// Which sprite ids are mission starts is NOT decided here; that comes
// from the census log on-device, in a later change. Every blip is
// published until then.
//
// Logic only, like weapons.cpp: no <windows.h>, the clock is passed
// in, the game is reached through blip_natives.h, so asi/test/ can run
// it. Everything runs on the game thread, from probes.cpp's on_frame
// (natives level 5) and commands::drain.

namespace blips {

// The FIRST/NEXT walk is keyed by blip type. Known types are 1..8;
// the walk covers a little past that in case a build has more. A type
// with no blips costs one native call.
constexpr uint32_t kMaxType = 12;

// Bound on one type's walk, in case NEXT never answers 0.
constexpr int kWalkGuard = 256;

// The map changes slowly; one census every two seconds is plenty and
// keeps the per-frame native count low (the hook runs every frame).
constexpr uint32_t kScanMs = 2000;

// Per frame, after the level-3 reads. Rescans when due, sets p.mission
// and p.route_blip, and drops the route if its blip went away. Returns
// true when it rescanned, i.e. when list() may have changed.
bool read(uint32_t ped, uint32_t now_ms, snapshot::Player& p);

// The last census, nearest first, for snapshot::publish_blips.
const snapshot::Blip* list(uint32_t* n);

// Turn the GPS route on for blip `id` (and off for the previous one).
// Returns why it was refused, or nullptr on success.
const char* route_to(int32_t id);

// Turn the route off and forget it.
void route_clear();

// Write the last census to the log.
void log_census();

// Forget everything remembered between frames. For tests.
void reset();

}  // namespace blips
