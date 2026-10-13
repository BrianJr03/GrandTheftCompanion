#pragma once

#include <cstddef>
#include <cstdint>

#include "snapshot.h"

// blips:: — the map's blips and the player's waypoint, GTA V edition.
//
// Why: the companion wants to route the player to the next mission
// without the pause-map dance. The game draws mission starts as blips,
// so the plugin walks every blip on the map (a census, raw sprite ids
// and all), publishes the nearest ones as a versioned list, and on
// request drops the player's waypoint on one of them with
// SET_NEW_WAYPOINT — the same waypoint the pause map sets, so the
// game's own GPS line follows.
//
// Which sprite ids are mission starts is NOT decided here. That is
// settled on-device from the census log and the companion's list, then
// encoded in a later change. Until then every blip is published.
//
// Everything here runs on the script fiber (script.cpp's tick and
// commands::drain).

namespace blips {

// Walk a slice of the sprite range each tick so a full pass costs a
// few hundred cheap native calls spread over ~0.6 s instead of one
// stall. kMaxSprite is past the last radar_* index any build defines.
constexpr int32_t kMaxSprite      = 1024;
constexpr int32_t kSpritesPerTick = 64;

// A waypoint found within this many metres (x/y) of where route_to()
// put it is still "ours". The game may snap the point to a road.
constexpr float kOursToleranceM = 25.0f;

// Script fiber, once per in-game tick, after the session flags are in
// `s`. Advances the census and fills s.waypoint_active / waypoint_ours
// / route_blip.
void tick(int32_t ped, snapshot::State& s);

// Set the player's waypoint on the published blip `id`. On failure
// `err` gets the reason (NUL-terminated, at most `cap` bytes).
bool route_to(int32_t id, char* err, size_t cap);

// Remove the player's waypoint and forget what route_to() set.
void route_clear();

// Write the whole published blip table to the log.
void log_census();

}  // namespace blips
