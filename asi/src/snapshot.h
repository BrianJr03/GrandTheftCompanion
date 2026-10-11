#pragma once

#include <cstdint>

// snapshot:: — one-writer (game thread) / many-readers (socket workers)
// handoff of player state. The writer calls `publish(...)` under a mutex;
// readers call `read(...)` which copies the latest struct out. There is no
// ring buffer — a HUD only ever cares about the newest sample, so dropping
// older ones is correct.

namespace snapshot {

struct Player {
    bool in_game;          // true when CPlayerInfo + CPed are both live
    bool in_vehicle;       // true when IS_CHAR_IN_ANY_CAR

    // --- stats ---
    uint32_t money;        // GET_SCORE / STORE_SCORE
    float health;          // GET_CHAR_HEALTH; GTA IV reports 100..200
    uint16_t max_health;   // CPlayerInfo field
    uint16_t armor;        // GET_CHAR_ARMOUR
    uint16_t max_armor;    // CPlayerInfo field
    uint8_t wanted_level;  // STORE_WANTED_LEVEL

    // --- weapon ---
    int32_t weapon_id;     // GET_CURRENT_CHAR_WEAPON
    int32_t ammo_total;    // GET_AMMO_IN_CHAR_WEAPON
    int32_t ammo_clip;     // GET_AMMO_IN_CLIP

    // Up to 13 weapon slots on IV (slot 0 is unarmed/fist). Values are
    // weapon ids. 0 slots mean no weapon in that slot.
    int32_t owned_weapons[13];
    int32_t owned_ammo[13];

    // --- vehicle (meaningful iff in_vehicle) ---
    uint32_t vehicle_model;    // GET_CAR_MODEL
    char vehicle_name[32];     // GET_DISPLAY_NAME_FROM_VEHICLE_MODEL
    float vehicle_speed_mps;   // GET_CAR_SPEED (m/s)
    float vehicle_engine_hp;   // GET_ENGINE_HEALTH

    // --- audio ---
    int32_t radio_station;     // GET_PLAYER_RADIO_STATION_INDEX; -1 = off

    uint64_t ts_ms;            // monotonic tick when published
};

// Writer: called from the game-thread per-frame hook once a per-frame
// read has filled a local Player.
void publish(const Player& p);

// Reader: copies the latest Player out and returns the ts_ms of that
// sample. Returns 0 ts_ms if no publish() has happened yet; the whole
// struct is in_game=false in that case.
uint64_t read(Player& out);

// True once publish() has been called at least once with in_game=true,
// i.e. we've actually seen a loaded save.
bool has_ever_been_in_game();

}  // namespace snapshot
