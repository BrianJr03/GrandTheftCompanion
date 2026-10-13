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
    bool paused;           // true when the user has the pause menu open
                           // (CTimer::ms_bUserPause). Set even when the
                           // natives chain is bypassed, so the HUD can
                           // flip to a dedicated paused view.

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

    // Indexed by the scripting eWeaponSlot (13 names; slot 0 is
    // unarmed/fist). Values are weapon ids, 0 = nothing in that slot.
    // Only slots weapons::kFirstSlot..kLastSlot are ever filled: see
    // weapons.h for why the scan stops short of 13.
    int32_t owned_weapons[13];
    int32_t owned_ammo[13];
    // True when owned_weapons is a real scan result (natives level 4).
    // False in builds without the scan, where the arrays are all zero
    // and say nothing about what the player carries.
    bool owned_valid;

    // --- weapon wheel ---
    // LB / L1 held on foot for long enough that the companion should
    // show its weapon wheel. Natives level 4 only.
    bool wheel;

    // --- map / route (natives level 5 only) ---
    bool    mission;       // GET_MISSION_FLAG: a mission script is running
    int32_t route_blip;    // blip the GPS route was last pointed at by
                           // route_blip, 0 = none (see blips.h)

    // --- vehicle (meaningful iff in_vehicle) ---
    uint32_t vehicle_model;    // GET_CAR_MODEL
    char vehicle_name[32];     // GET_DISPLAY_NAME_FROM_VEHICLE_MODEL
    float vehicle_speed_mps;   // GET_CAR_SPEED (m/s)
    float vehicle_engine_hp;   // GET_ENGINE_HEALTH

    // --- audio ---
    int32_t radio_station;     // GET_PLAYER_RADIO_STATION_INDEX; -1 = off

    // --- episode ---
    // 0 = Grand Theft Auto IV
    // 1 = The Lost and Damned
    // 2 = The Ballad of Gay Tony
    uint32_t episode;          // GET_CURRENT_EPISODE

    uint64_t ts_ms;            // monotonic tick when published
};

// MUST be called once from the main startup worker before any publish/
// read happens. Initializes the critical section. Calling publish/read
// without this is undefined.
void init();

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

// --- map blips (natives level 5) ---
// One map blip as the game reports it, nearest first. Raw: which
// sprites are mission starts is settled on-device from this census.
struct Blip {
    int32_t id;        // blip handle; valid while the blip exists
    int32_t sprite;    // GET_BLIP_SPRITE
    int32_t colour;    // GET_BLIP_COLOUR
    int32_t type;      // GET_BLIP_INFO_ID_TYPE (the FIRST/NEXT walk key)
    int32_t display;   // GET_BLIP_INFO_ID_DISPLAY
    float   x, y, z;
    float   dist_m;    // from the player ped at scan time
};

constexpr uint32_t kMaxBlips = 64;

struct Blips {
    uint32_t ver;      // bumped whenever the list content changes
    uint32_t n;
    Blip     list[kMaxBlips];
};

// Writer (game thread): replace the list; bumps ver only on change.
void publish_blips(const Blip* list, uint32_t n);
// Reader: copy the list out. Version peek so the bridge can skip the
// copy at 20 Hz.
void read_blips(Blips& out);
uint32_t blips_version();

}  // namespace snapshot
