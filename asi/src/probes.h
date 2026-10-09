#pragma once

#include <cstdint>

// probes:: — read GTA IV player state via hand-rolled offset reads. We
// originally planned to use IV-SDK but its headers lack include guards and
// rely on MSVC-only parser quirks, so we treat IV-SDK as a reference source
// for offsets and function RVAs rather than linking it. See probes.cpp.

namespace probes {

struct PlayerState {
    bool valid;          // false until we're actually in-game
    float health;        // current health, matches CPed::m_fPedHealth
    uint32_t max_health; // CPlayerInfo::m_nMaxHealth
    uint32_t max_armor;  // CPlayerInfo::m_nMaxArmor
};

// Resolve the IV-SDK address table against the running exe. Returns the
// detected game version as a short label ("classic 1.0.8.0", "unknown").
const char* init();

// Sample current player state. Returns valid=false if the player hasn't
// loaded yet (title screen, loading, cutscene without CPlayerInfo).
PlayerState sample();

}  // namespace probes
