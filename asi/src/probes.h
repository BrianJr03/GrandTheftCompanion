#pragma once

#include <cstdint>

// probes:: — the game-state reader. Phase 1 is deliberately safe-mode: no
// memory reads, no hooks, no native invocations. sample() always returns
// an empty PlayerState with in_game=false. Phase 2 will replace the body
// with CE-correct byte-pattern scans + script natives called from the
// game's own thread.

namespace probes {

struct PlayerState {
    bool in_game;        // true when a CPlayerInfo + CPed are live
    // Phase 2 extends this with health, armor, money, weapon, vehicle, ...
};

// Attempt to enable the probe path. In Phase 1 this never succeeds: the
// ASI stays in safe mode regardless of which exe is loaded. The returned
// string is a short, log-friendly reason.
const char* init();

// True once init() has concluded the current exe is supported AND all
// required pattern scans / hooks succeeded. Phase 1 always returns false.
bool enabled();

// Sample the game. Phase 1: always {in_game=false}.
PlayerState sample();

}  // namespace probes
