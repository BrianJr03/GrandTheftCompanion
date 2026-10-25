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

// Vehicle-mod catalogue limits. Base-game Benny's / shop maxes out
// around 50 options on a wheel slot; 64 is comfortably above. 40
// slots covers every walkable modType today (0..48, minus the
// three toggle slots and the ones no stock car exposes). The frame
// loudly logs if a car exceeds either cap.
constexpr size_t kMaxModOptions = 64;
constexpr size_t kMaxModSlots   = 40;
constexpr size_t kMaxModToggles = 4;    // slots 18 / 20 / 22 today
constexpr size_t kPlateLen      = 12;   // 8 chars + wiggle room

// Armory (per-weapon Ammu-Nation page). Max observed on Pistol Mk II
// in Gate A0 was 22 components spread over 6 bone groups; 32 / 8 is
// comfortable headroom. The frame drops any option past kMaxArmory
// Components and logs it.
constexpr size_t kMaxArmoryComponents = 32;
constexpr size_t kMaxArmoryGroups     = 8;

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

    // Dpad-down just-pressed this frame (edge-triggered — true for the
    // one frame right after a press, false otherwise). Companion
    // toggles its blips panel on each true. Reads
    // INPUT_CELLPHONE_DOWN which is always bound to the dpad-down
    // button in GTA V regardless of what the player assigned to the
    // in-game crouch action.
    bool dpad_down_edge;

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
    // Paint. GET_VEHICLE_COLOURS is a pair of palette indices; the two
    // custom flags are true when the matching layer carries an RGB
    // override that would hide the palette index on screen. Read every
    // tick in-vehicle so the companion's Paint section stays correct
    // after a shop respray or a mod menu.
    int32_t  vehicle_colour1;
    int32_t  vehicle_colour2;
    bool     vehicle_custom;              // either layer has an RGB override

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

    // --- cheats ---
    // Toggle cheats currently on, as a bitmask indexed by `cheat_id -
    // 20` (one-shots do not light a bit). Mirrors asi/src/snapshot.h.
    uint32_t cheats_mask;

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

// One modifiable slot on a vehicle (spoiler, engine, wheels, ...).
// Options carry the game's own localised label; the slot name uses
// the shop's label when GET_MOD_SLOT_NAME resolves, otherwise ""
// so the app falls back to its own slot-N string. kIndexStock
// (-1 at the game level, 0xFFFF on the wire) is "nothing fitted".
struct ModSlot {
    int32_t  slot;                             // modType, 0..48
    int32_t  cur;                              // GET_VEHICLE_MOD; -1 = stock
    uint32_t n_options;
    char     name[kLabelLen];                  // "" if no GXT label
    char     options[kMaxModOptions][kLabelLen];
};

// One three-state toggle (18 turbo, 20 tyre smoke, 22 xenon). The
// app renders these as a switch instead of ◀ ▶.
struct ModToggle {
    int32_t slot;
    bool    on;
    char    name[kLabelLen];
};

struct Mods {
    uint32_t ver;                              // bumped on any change
    uint32_t vehicle;                          // vehicle model hash the
                                               // catalogue was built for
    bool     kit;                              // this vehicle has a mod kit
    int32_t  wheel_type;                       // GET_VEHICLE_WHEEL_TYPE
    int32_t  tints;                            // GET_NUM_VEHICLE_WINDOW_TINTS
    int32_t  tint;                             // GET_VEHICLE_WINDOW_TINT
    int32_t  plate_style;                      // palette index
    bool     neon;                             // any neon side enabled
    bool     bulletproof;                      // tyres cannot burst
    char     plate[kPlateLen];                 // GET_VEHICLE_NUMBER_PLATE_TEXT

    uint32_t n_slots;
    ModSlot  slots[kMaxModSlots];

    uint32_t n_toggles;
    ModToggle toggles[kMaxModToggles];
};

// One attachment option on an Ammu-Nation page. `bone` is the opaque
// attachBone id from GET_DLC_WEAPON_COMPONENT_DATA — the app groups
// options with the same bone into one slot (clip / scope / muzzle /
// grip / flashlight / finish). `is_default` marks the `WCT_CLIP1`-
// style stock option, which the game treats as never-removable: the
// walker records it so the app renders ◀ ▶ with wrap-through-default,
// never "remove to nothing". `is_fitted` is per-ped state, read right
// before publish so the "cur" selection on the sheet matches the gun.
struct ArmoryOption {
    int32_t  cidx;                             // DLC component index
    uint32_t bone;                             // attachBone
    bool     is_default;                       // bActiveByDefault
    bool     is_fitted;                        // HAS_PED_GOT_WEAPON_COMPONENT
    char     name[kLabelLen];                  // localised label, "" if missing
};

// One weapon's Ammu-Nation page. widx < 0 means the weapon is owned
// but has no DLC-catalogue entry (knife, grenade, base-vanilla pistol
// / carbine / etc.) — the app shows "no attachments" and hides the
// Tint and Ammo sections for v1. Everything else carries the live
// per-ped values (tint index, ammo count), re-read on every
// publish so a successful apply bumps `ver` and the sheet follows.
struct Armory {
    uint32_t ver;                              // bumped on any change
    int32_t  widx;                             // DLC weapon index, -1 = not in catalogue
    uint32_t weapon;                           // joaat weapon hash
    char     name[kLabelLen];                  // localised weapon name
    int32_t  tints;                            // GET_WEAPON_TINT_COUNT
    int32_t  tint;                             // GET_PED_WEAPON_TINT_INDEX
    int32_t  ammo;                             // GET_AMMO_IN_PED_WEAPON
    int32_t  max_ammo;                         // GET_MAX_AMMO
    int32_t  clip;                             // GET_WEAPON_CLIP_SIZE (current fit)
    uint32_t n_options;
    ArmoryOption options[kMaxArmoryComponents];
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

    uint32_t mods_ver;      // 0 while no catalogue has been published
    Mods     mods;

    uint32_t armory_ver;    // 0 while no armory page has been published
    Armory   armory;
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
// Replace the vehicle-mods catalogue. Always bumps the version — the
// catalogue walker already knows when it has something new to publish
// (option count moved, slot added, toggle flipped by a set_mod).
void publish_mods(const Mods& m);
// Clear the catalogue (vehicle changed / player left the car). Zeroes
// n_slots / n_toggles and bumps mods_ver so the client reads an empty
// list rather than a stale one.
void clear_mods();

// Replace the current Ammu-Nation page. Always bumps `armory_ver`.
// The walker calls this on first catalogue read and after any
// successful set_component / set_weapon_tint / set_ammo.
void publish_armory(const Armory& a);
// Clear the page (session reset, character switch, player went
// online). Zeroes n_options and bumps armory_ver so the client
// drops its sheet rather than acting on a stale page.
void clear_armory();

// Reader side. read() returns the ts_ms of the sample (0 = nothing
// published yet). read_lists() copies both lists out.
uint64_t read(State& out);
void read_lists(Lists& out);
// Cheap version peek so the bridge can skip copying ~20 KB at 20 Hz.
void list_versions(uint32_t* weapons_ver, uint32_t* stations_ver,
                   uint32_t* blips_ver, uint32_t* mods_ver,
                   uint32_t* armory_ver);

// Station id for a published index, copied into `out`. False if out of
// range. Used by commands:: to turn a client's index into a name.
bool station_name(uint32_t index, char* out, size_t cap);

// The published blip with handle `id`, copied into `out`. False if the
// last scan did not see it. Used by commands:: so a client can only
// route to a blip it was told about.
bool blip_by_id(int32_t id, Blip* out);

}  // namespace snapshot
