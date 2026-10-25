#pragma once

#include <cstdint>

// armory_probe:: — Gate A0 one-shot. Dumps what the game actually has
// in its DLC-weapon catalogue so the v1 build can decide between
//
//   A. driving everything off GET_DLC_WEAPON_DATA / _COMPONENT_DATA
//      (if base-game weapons are in that list), or
//
//   B. a hand-maintained components.h table filtered at runtime by
//      DOES_WEAPON_TAKE_WEAPON_COMPONENT (if the list is DLC-only).
//
// Compiled in only when -DTHORBRIDGE_ARMORY_PROBE=ON. Removed before
// v1 ships.

namespace armory_probe {

// Script fiber: walk the catalogue, hex-dump the raw bytes of a few
// weapons and their components, and log tint / clip counts. Call once
// per button press. Expensive (~thousands of log lines on disk); the
// caller refuses it when a non-foot state is active.
void run(int32_t ped);

}  // namespace armory_probe
