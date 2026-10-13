#pragma once

#include <cstddef>
#include <cstdint>

// snapshot:: — one-writer (script fiber) / many-readers (socket
// workers) handoff of game state, mirroring asi/src/snapshot.h.
//
// Two things are published:
//   * State — small, rewritten every frame, sent to clients at 20 Hz.
//   * Lists — the owned-weapon list, the radio-station list and the
//     map-blip list. All are bulky and change rarely, so they carry
//     version counters and the bridge only sends them on connect and
//     when a counter moves.

namespace snapshot {

constexpr size_t kNameLen     = 48;   // GXT keys / station ids
constexpr size_t kLabelLen    = 64;   // localised display strings (UTF-8)
constexpr size_t kMaxWeapons  = 128;
constexpr size_t kMaxStations = 64;
// Story mode keeps well under a hundred blips on the map at once
// (shops, properties, contacts, activities); the scan keeps the
// nearest ones when there are more.
constexpr size_t kMaxBlips    = 96;

// Which protagonist the player ped currently is.
enum Character : int8_t {
    kCharOther    = -1,  // Director Mode actor, animal, cutscene ped...
    kCharMichael  = 0,   // player_zero
    kCharFranklin = 1,   // player_one
    kCharTrevor   = 2,   // player_two
};

struct State {
    // --- session flags ---
    bool in_game;         // a player ped exists and no loading screen
    bool online;          // GTA Online session: everything else is blank
    bool paused;          // pause menu open
    bool switching;       // character-switch camera in progress
    bool cutscene;        // scripted cutscene playing
    bool mission;         // GET_MISSION_FLAG
    bool animal;          // peyote / Director Mode animal form

    // --- weapon wheel ---
    // select_held: INPUT_SELECT_WEAPON (LB / L1 / Tab) is down this tick
    //   and the player is in a state where a weapon can be picked. Not
    //   sent to clients; commands:: uses it to defer a touch pick.
    // wheel: the same, but only once it has been down long enough that
    //   the game's own weapon wheel is on screen (a quick tap is the
    //   game's holster/unholster shortcut, not a wheel). Sent to clients
    //   so the companion can show its wheel for as long as it is held.
    bool select_held;
    bool wheel;

    // --- who ---
    int8_t   character;   // Character
    uint32_t ped_model;   // raw model hash, for the "other" case

    // --- stats ---
    bool    money_valid;  // false for non-protagonists (no SPn_ stat)
    int32_t money;        // SPn_TOTAL_CASH for the current character
    int32_t health;       // GET_ENTITY_HEALTH; protagonists: 100..200
    int32_t max_health;   // GET_ENTITY_MAX_HEALTH
    int32_t armor;        // GET_PED_ARMOUR
    int32_t max_armor;    // GET_PLAYER_MAX_ARMOUR
    uint8_t wanted;       // 0..5 (GTA V has five stars, IV had six)

    // --- special ability (GTA V only) ---
    // The game exposes no script native for the meter's fill level,
    // only these states, so the HUD shows a state rather than a %.
    bool special_unlocked;
    bool special_enabled;
    bool special_active;
    bool special_full;

    // --- weapon ---
    uint32_t weapon;                 // GET_SELECTED_PED_WEAPON (joaat hash)
    char     weapon_name[kNameLen];  // "weapon_pistol", or "" if not in our table
    uint32_t weapon_group;           // GET_WEAPONTYPE_GROUP
    int32_t  ammo_total;
    int32_t  ammo_clip;
    int32_t  clip_size;

    // --- vehicle (meaningful iff in_vehicle) ---
    bool     in_vehicle;
    uint32_t vehicle_model;
    int32_t  vehicle_class;               // natives::VehicleClass
    char     vehicle_name[kLabelLen];     // localised ("Adder")
    float    vehicle_speed_mps;
    float    vehicle_engine_hp;           // -4000..1000
    float    vehicle_body_hp;             // 0..1000
    float    vehicle_altitude_m;          // above ground; aircraft only
    bool     vehicle_has_radio;

    // --- audio ---
    int32_t radio_index;                  // -1 = off
    char    radio_name[kNameLen];         // "RADIO_01_CLASS_ROCK", "" = off

    // --- map waypoint ---
    // waypoint_active: IS_WAYPOINT_ACTIVE, whoever placed it.
    // waypoint_ours: it sits where the last route_blip command put it,
    //   so the companion may move or clear it without stepping on one
    //   the player placed by hand.
    // route_blip: the blip id that command targeted, 0 = none.
    bool    waypoint_active;
    bool    waypoint_ours;
    int32_t route_blip;

    uint64_t ts_ms;                       // GetTickCount64 at publish
};

struct Weapon {
    uint32_t hash;
    uint32_t group;
    int32_t  ammo;
    char     name[kNameLen];
};

struct Station {
    char name[kNameLen];    // stable id, e.g. "RADIO_01_CLASS_ROCK"
    char label[kLabelLen];  // localised ("Los Santos Rock Radio"), may be ""
};

// One map blip as the game reports it. Raw on purpose: which sprites
// are mission starts is settled on-device from this census, so nothing
// here interprets them.
struct Blip {
    int32_t id;        // blip handle; valid while the blip exists
    int32_t sprite;    // GET_BLIP_SPRITE (radar_* index)
    int32_t colour;    // GET_BLIP_COLOUR
    int32_t type;      // GET_BLIP_INFO_ID_TYPE
    int32_t display;   // GET_BLIP_INFO_ID_DISPLAY
    float   x, y, z;
    float   dist_m;    // from the player ped at scan time
};

struct Lists {
    uint32_t weapons_ver;   // bumped whenever weapons[] content changes
    uint32_t n_weapons;
    Weapon   weapons[kMaxWeapons];

    uint32_t stations_ver;  // bumped whenever stations[] content changes
    uint32_t n_stations;
    Station  stations[kMaxStations];

    uint32_t blips_ver;     // bumped whenever blips[] content changes
    uint32_t n_blips;
    Blip     blips[kMaxBlips];
};

// Call once before any other function (startup worker).
void init();

// Writer side (script fiber).
void publish(const State& s);
// Replace the weapon / station list; bumps the version only when the
// content actually differs from what is already published.
void publish_weapons(const Weapon* list, uint32_t n);
void publish_stations(const Station* list, uint32_t n);
void publish_blips(const Blip* list, uint32_t n);

// Reader side. read() returns the ts_ms of the sample (0 = nothing
// published yet). read_lists() copies both lists out.
uint64_t read(State& out);
void read_lists(Lists& out);
// Cheap version peek so the bridge can skip copying ~20 KB at 20 Hz.
void list_versions(uint32_t* weapons_ver, uint32_t* stations_ver,
                   uint32_t* blips_ver);

// Station id for a published index, copied into `out`. False if out of
// range. Used by commands:: to turn a client's index into a name.
bool station_name(uint32_t index, char* out, size_t cap);

// The published blip with handle `id`, copied into `out`. False if the
// last scan did not see it. Used by commands:: so a client can only
// route to a blip it was told about.
bool blip_by_id(int32_t id, Blip* out);

}  // namespace snapshot
