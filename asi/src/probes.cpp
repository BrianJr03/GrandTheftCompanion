// Phase 1 implementation: safe mode. No memory reads, no hooks, no
// native invocations. The ASI loads, serves a hello frame + tick
// counter, and otherwise stays out of the game's way.
//
// Phase 2 replaces this with CE 1.2.0.59-correct byte-pattern scans and
// script-native invocations driven from the game's own thread.

#include "probes.h"

namespace probes {

const char* init() {
    return "safe-mode (Phase 1: no reads, no hooks)";
}

bool enabled() {
    return false;
}

PlayerState sample() {
    return PlayerState{};  // in_game=false
}

}  // namespace probes
