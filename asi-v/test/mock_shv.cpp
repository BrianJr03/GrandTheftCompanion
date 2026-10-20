// Stand-in for ScriptHookV.dll, for exercising ThorBridgeV.asi without
// the game. NOT Script Hook V and contains none of its code: it exports
// the same decorated symbol names (see mock_shv.def) and answers the
// handful of natives this plugin calls with canned story-mode values.
//
// What it proves: the plugin's export binding, native argument/return
// marshalling, JSON framing, list versioning, command round-trips and
// stall detection. What it cannot prove: anything about the real game
// or the real Script Hook V. See test/README.md.
//
// Scenario is picked with the MOCK_SCENARIO environment variable:
//   story   (default) Michael on foot -> in a car -> switch to Franklin
//   online  NETWORK_IS_SESSION_STARTED is true
//   loading loading screen never clears
//   other   player ped is not one of the three protagonists
//   stall   script fiber stops being resumed after 3 s (pause menu)
//   wheel   Michael on foot throughout; the weapon-wheel control is held
//           from 3.0 s to 5.5 s, and shortly after it is released the
//           mock "game" re-selects the weapon that was in hand when the
//           wheel opened (what the real wheel is expected to do when it
//           closes on its unchanged highlight)
//   blips   Michael on foot throughout, with three blips on the map; at
//           4 s the mock "player" drags the waypoint elsewhere by hand
//
// Every scenario has the same three map blips, so the blip census and
// the waypoint natives are exercised by all of them.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "../src/joaat.h"
#include "../src/natives.h"

namespace {

using ScriptMain = void (*)();

enum Scenario { kStory, kOnline, kLoading, kOther, kStall, kWheel, kBlips, kCheats };
Scenario g_scenario = kStory;
uint64_t g_t0 = 0;

// ---- fake world ------------------------------------------------------

constexpr int kPlayer = 0;
constexpr int kPed = 1001;
constexpr int kVeh = 2002;

const char* kStations[] = {
    "RADIO_01_CLASS_ROCK", "RADIO_02_POP", "RADIO_03_HIPHOP_NEW",
    "RADIO_19_USER", "RADIO_99_MOCK_UNLABELLED",
};
constexpr int kNumStations = sizeof(kStations) / sizeof(kStations[0]);

struct Label { const char* key; const char* text; };
const Label kLabels[] = {
    {"RADIO_01_CLASS_ROCK", "Los Santos Rock Radio"},
    {"RADIO_02_POP",        "Non-Stop-Pop FM"},
    {"RADIO_03_HIPHOP_NEW", "Radio Los Santos"},
    {"RADIO_19_USER",       "Self Radio"},
    // Exercises JSON escaping and UTF-8 pass-through.
    {"ADDER",               "Adder \"S\" \xC3\xA9"},
};

int      g_radio = 0;                         // index into kStations, -1 off
uint32_t g_weapon = joaat("weapon_pistol");
const uint32_t kOwned[] = {
    joaat("weapon_pistol"), joaat("weapon_assaultrifle"),
    joaat("weapon_knife"),  joaat("weapon_grenade"),
};

// Cheats mock state. Written by the natives the cheats subsystem calls;
// read by the state frame (via the existing GET_* paths) so the Python
// scenario can watch each tap take effect across a frame.
int32_t  g_mock_health   = 175;
int32_t  g_mock_armor    = 40;
int32_t  g_mock_wanted   = 3;
int      g_wanted_apply_calls = 0;
bool     g_invincible    = false;
bool     g_infinite_clip = false;
float    g_run_mult      = 1.0f;
int32_t  g_gravity       = 0;
float    g_time_scale    = 1.0f;
bool     g_police_ignore = false;
int      g_fix_calls     = 0;
int      g_dirt_calls    = 0;
int      g_wash_calls    = 0;
int      g_given_count   = 0;
int      g_ammo_set_count = 0;
int      g_special_fills = 0;
int      g_cheat_stat_set = 0;
int32_t  g_veh_engine_hp = 870;    // matches GET_VEHICLE_ENGINE_HEALTH story mock
int32_t  g_veh_body_hp   = 990;
float    g_veh_dirt      = 0.5f;
char     g_weather_cur[32] = {0};
int      g_weather_calls = 0;
int      g_weather_cleared = 0;
int32_t  g_clock_h       = 7;
int32_t  g_clock_m       = 30;
int      g_clock_set_calls = 0;

// Spawn-car state machine: REQUEST_MODEL marks the model pending;
// HAS_MODEL_LOADED returns false for kSpawnLoadFrames polls, then true.
// This exercises the plugin's wait-across-ticks path end to end.
uint32_t g_pending_model = 0;
int      g_model_poll    = 0;
constexpr int kSpawnLoadFrames = 2;   // ~80 ms wait at 25 Hz
int32_t  g_spawn_last_veh = 0;
bool     g_spawn_released = false;    // SET_VEHICLE_AS_NO_LONGER_NEEDED seen

uint64_t elapsed_ms() { return GetTickCount64() - g_t0; }

// ---- fake map --------------------------------------------------------

// Player stands at the origin. Three blips with distinct sprites, plus
// the waypoint blip (sprite 8, the real game's radar_waypoint) which
// exists only while a waypoint is set.
constexpr float kPlayerX = 0.0f, kPlayerY = 0.0f, kPlayerZ = 10.0f;
constexpr int kWaypointSprite = 8;
constexpr int kWaypointBlip = 777;
struct MapBlip { int id; int sprite; int colour; int type; float x, y, z; };
const MapBlip kMap[] = {
    {301, 78, 0, 4, 300.0f, 400.0f, 10.0f},   // "Michael" contact, 500 m
    {302,  1, 5, 4,  30.0f,  40.0f, 10.0f},   // plain radar_level, 50 m
    {303, 52, 2, 4, 3000.0f, 4000.0f, 10.0f}, // far-away shop, 5000 m
};
constexpr int kNumMap = sizeof(kMap) / sizeof(kMap[0]);
bool  g_waypoint = false;
float g_wp_x = 0.0f, g_wp_y = 0.0f;
int   g_walk_sprite = -1;   // sprite the FIRST/NEXT walk is on
int   g_walk_pos = 0;       // how many of that sprite's blips were handed out

const MapBlip* map_blip(int id) {
    for (const MapBlip& m : kMap) if (m.id == id) return &m;
    return nullptr;
}
int walk_next() {
    // Every map blip has a distinct sprite, so each walk yields at most
    // one map blip; the waypoint blip has its own sprite.
    if (g_walk_sprite == kWaypointSprite) {
        return (g_waypoint && g_walk_pos++ == 0) ? kWaypointBlip : 0;
    }
    for (const MapBlip& m : kMap) {
        if (m.sprite == g_walk_sprite) return g_walk_pos++ == 0 ? m.id : 0;
    }
    return 0;
}
void ret_vec3(float x, float y, float z);

// blips timeline: the player drags the waypoint by hand at 4 s.
constexpr uint64_t kDragMs = 4000;
bool g_dragged = false;
void blips_step() {
    if (g_scenario != kBlips || g_dragged || elapsed_ms() < kDragMs) return;
    if (!g_waypoint) return;
    g_dragged = true;
    g_wp_x += 500.0f;
    g_wp_y += 500.0f;
    std::fprintf(stderr, "[mock] player dragged the waypoint to (%.0f, %.0f)\n",
                 g_wp_x, g_wp_y);
    std::fflush(stderr);
}

// story timeline
bool in_vehicle()  { return g_scenario == kStory && elapsed_ms() > 2000; }
bool switching()   { uint64_t t = elapsed_ms();
                     return g_scenario == kStory && t > 5000 && t < 6000; }
uint32_t ped_model() {
    if (g_scenario == kOther) return joaat("a_c_deer");
    return elapsed_ms() > 5500 && g_scenario == kStory
        ? joaat("player_one") : joaat("player_zero");
}

// wheel timeline
constexpr uint64_t kWheelDownMs   = 3000;
constexpr uint64_t kWheelUpMs     = 5500;
constexpr uint64_t kWheelCommitMs = 100;   // release -> game commits highlight
bool select_held() { uint64_t t = elapsed_ms();
                     return g_scenario == kWheel && t >= kWheelDownMs
                         && t < kWheelUpMs; }
bool     g_was_held = false;
uint32_t g_wheel_weapon = 0;      // in hand when the wheel opened
uint64_t g_wheel_commit_at = 0;   // elapsed_ms deadline, 0 = none pending

// Once per mock frame: stand in for the game's own weapon wheel.
void wheel_step() {
    if (g_scenario != kWheel) return;
    const bool held = select_held();
    if (held && !g_was_held) g_wheel_weapon = g_weapon;
    if (!held && g_was_held) g_wheel_commit_at = elapsed_ms() + kWheelCommitMs;
    g_was_held = held;
    if (g_wheel_commit_at && elapsed_ms() >= g_wheel_commit_at) {
        g_wheel_commit_at = 0;
        std::fprintf(stderr, "[mock] wheel commit 0x%08X -> 0x%08X\n",
                     g_weapon, g_wheel_weapon);
        std::fflush(stderr);
        g_weapon = g_wheel_weapon;
    }
}

// ---- native call plumbing --------------------------------------------

uint64_t g_hash = 0;
uint64_t g_args[32];
int      g_argc = 0;
uint64_t g_ret[4];
unsigned long long g_calls = 0;

template <typename T> T arg(int i) {
    T v{};
    std::memcpy(&v, &g_args[i], sizeof(T));
    return v;
}
template <typename T> void ret(T v) {
    g_ret[0] = 0;
    std::memcpy(&g_ret[0], &v, sizeof(T));
}
void ret_bool(bool v) { ret<uint64_t>(v ? 1 : 0); }
// Vector3 returns: three floats in three padded slots, like the game.
void ret_vec3(float x, float y, float z) {
    g_ret[0] = g_ret[1] = g_ret[2] = 0;
    std::memcpy(&g_ret[0], &x, sizeof(float));
    std::memcpy(&g_ret[1], &y, sizeof(float));
    std::memcpy(&g_ret[2], &z, sizeof(float));
}

bool expect_argc(int n, const char* name) {
    if (g_argc == n) return true;
    std::fprintf(stderr, "[mock] ARITY MISMATCH %s: got %d args, want %d\n",
                 name, g_argc, n);
    std::fflush(stderr);
    ExitProcess(3);
    return false;
}
void dispatch() {
    using namespace natives;
    g_ret[0] = 0;
    switch (g_hash) {
    case NETWORK_IS_SESSION_STARTED: expect_argc(0, "NETWORK_IS_SESSION_STARTED");
        ret_bool(g_scenario == kOnline); break;
    case PLAYER_ID: expect_argc(0, "PLAYER_ID"); ret<int>(kPlayer); break;
    case PLAYER_PED_ID: expect_argc(0, "PLAYER_PED_ID"); ret<int>(kPed); break;
    case DOES_ENTITY_EXIST: expect_argc(1, "DOES_ENTITY_EXIST");
        ret_bool(arg<int>(0) == kPed || arg<int>(0) == kVeh); break;
    case GET_IS_LOADING_SCREEN_ACTIVE: expect_argc(0, "GET_IS_LOADING_SCREEN_ACTIVE");
        ret_bool(g_scenario == kLoading); break;
    case IS_PAUSE_MENU_ACTIVE: expect_argc(0, "IS_PAUSE_MENU_ACTIVE"); ret_bool(false); break;
    case IS_PLAYER_SWITCH_IN_PROGRESS: expect_argc(0, "IS_PLAYER_SWITCH_IN_PROGRESS");
        ret_bool(switching()); break;
    case IS_CUTSCENE_PLAYING: expect_argc(0, "IS_CUTSCENE_PLAYING"); ret_bool(false); break;
    case GET_MISSION_FLAG: expect_argc(0, "GET_MISSION_FLAG"); ret_bool(false); break;
    case GET_IS_PLAYER_IN_ANIMAL_FORM: expect_argc(0, "GET_IS_PLAYER_IN_ANIMAL_FORM");
        ret_bool(g_scenario == kOther); break;
    case GET_ENTITY_MODEL: expect_argc(1, "GET_ENTITY_MODEL");
        ret<uint32_t>(arg<int>(0) == kVeh ? joaat("adder") : ped_model()); break;
    case GET_ENTITY_HEALTH: expect_argc(1, "GET_ENTITY_HEALTH");
        ret<int>(g_mock_health); break;
    case GET_ENTITY_MAX_HEALTH: expect_argc(1, "GET_ENTITY_MAX_HEALTH"); ret<int>(200); break;
    case GET_PED_ARMOUR: expect_argc(1, "GET_PED_ARMOUR");
        ret<int>(g_mock_armor); break;
    case GET_PLAYER_MAX_ARMOUR: expect_argc(1, "GET_PLAYER_MAX_ARMOUR"); ret<int>(100); break;
    case GET_PLAYER_WANTED_LEVEL: expect_argc(1, "GET_PLAYER_WANTED_LEVEL");
        ret<int>(g_mock_wanted); break;
    case STAT_GET_INT: { expect_argc(3, "STAT_GET_INT");
        const uint32_t stat = arg<uint32_t>(0);
        int* out = arg<int*>(1);
        int v = -1;
        if (stat == joaat("SP0_TOTAL_CASH")) v = 1234567;
        if (stat == joaat("SP1_TOTAL_CASH")) v = 7654321;
        if (stat == joaat("SP2_TOTAL_CASH")) v = 42;
        if (arg<int>(2) != -1) {
            std::fprintf(stderr, "[mock] STAT_GET_INT p2 != -1\n");
            ExitProcess(3);
        }
        if (out && v >= 0) *out = v;
        ret_bool(v >= 0); break; }
    case IS_SPECIAL_ABILITY_UNLOCKED: expect_argc(1, "IS_SPECIAL_ABILITY_UNLOCKED");
        ret_bool(true); break;
    case IS_SPECIAL_ABILITY_ENABLED: expect_argc(2, "IS_SPECIAL_ABILITY_ENABLED");
        ret_bool(true); break;
    case IS_SPECIAL_ABILITY_ACTIVE: expect_argc(2, "IS_SPECIAL_ABILITY_ACTIVE");
        ret_bool(false); break;
    case IS_SPECIAL_ABILITY_METER_FULL: expect_argc(2, "IS_SPECIAL_ABILITY_METER_FULL");
        ret_bool(true); break;
    case GET_SELECTED_PED_WEAPON: expect_argc(1, "GET_SELECTED_PED_WEAPON");
        ret<uint32_t>(g_weapon); break;
    case GET_WEAPONTYPE_GROUP: expect_argc(1, "GET_WEAPONTYPE_GROUP");
        ret<uint32_t>(joaat("GROUP_PISTOL")); break;
    case GET_AMMO_IN_PED_WEAPON: expect_argc(2, "GET_AMMO_IN_PED_WEAPON"); ret<int>(120); break;
    case GET_AMMO_IN_CLIP: { expect_argc(3, "GET_AMMO_IN_CLIP");
        int* out = arg<int*>(2);
        if (out) *out = 9;
        ret_bool(true); break; }
    case GET_MAX_AMMO_IN_CLIP: expect_argc(3, "GET_MAX_AMMO_IN_CLIP"); ret<int>(12); break;
    case HAS_PED_GOT_WEAPON: { expect_argc(3, "HAS_PED_GOT_WEAPON");
        bool has = false;
        for (uint32_t h : kOwned) has |= (h == arg<uint32_t>(1));
        ret_bool(has); break; }
    case SET_CURRENT_PED_WEAPON: expect_argc(3, "SET_CURRENT_PED_WEAPON");
        g_weapon = arg<uint32_t>(1); break;
    case IS_CONTROL_PRESSED: { expect_argc(2, "IS_CONTROL_PRESSED");
        // Only the weapon-wheel control is ever polled; anything else
        // is a wrong constant in the plugin.
        if (arg<int>(0) != kPlayerControl || arg<int>(1) != kInputSelectWeapon) {
            std::fprintf(stderr, "[mock] UNEXPECTED CONTROL %d/%d\n",
                         arg<int>(0), arg<int>(1));
            std::fflush(stderr);
            ExitProcess(3);
        }
        ret_bool(select_held()); break; }
    case IS_CONTROL_JUST_PRESSED: { expect_argc(2, "IS_CONTROL_JUST_PRESSED");
        ret_bool(false); break; }
    case IS_DISABLED_CONTROL_PRESSED: { expect_argc(2, "IS_DISABLED_CONTROL_PRESSED");
        // Dpad-down for the blips toggle. No scenario presses it yet,
        // so always false — change this once a scenario needs to.
        ret_bool(false); break; }
    case IS_PED_IN_ANY_VEHICLE: expect_argc(2, "IS_PED_IN_ANY_VEHICLE");
        ret_bool(in_vehicle()); break;
    case GET_VEHICLE_PED_IS_IN: expect_argc(2, "GET_VEHICLE_PED_IS_IN");
        ret<int>(in_vehicle() ? kVeh : 0); break;
    case GET_VEHICLE_CLASS: expect_argc(1, "GET_VEHICLE_CLASS"); ret<int>(7); break;
    case GET_ENTITY_SPEED: expect_argc(1, "GET_ENTITY_SPEED"); ret<float>(31.5f); break;
    case GET_VEHICLE_ENGINE_HEALTH: expect_argc(1, "GET_VEHICLE_ENGINE_HEALTH");
        ret<float>(static_cast<float>(g_veh_engine_hp)); break;
    case GET_VEHICLE_BODY_HEALTH: expect_argc(1, "GET_VEHICLE_BODY_HEALTH");
        ret<float>(static_cast<float>(g_veh_body_hp)); break;
    case GET_ENTITY_HEIGHT_ABOVE_GROUND: expect_argc(1, "GET_ENTITY_HEIGHT_ABOVE_GROUND");
        ret<float>(0.5f); break;
    case DOES_PLAYER_VEH_HAVE_RADIO: expect_argc(0, "DOES_PLAYER_VEH_HAVE_RADIO");
        ret_bool(true); break;
    case GET_DISPLAY_NAME_FROM_VEHICLE_MODEL: expect_argc(1, "GET_DISPLAY_NAME_FROM_VEHICLE_MODEL");
        ret<const char*>("ADDER"); break;
    case GET_LABEL_TEXT: { expect_argc(1, "GET_LABEL_TEXT");
        const char* key = arg<const char*>(0);
        const char* text = "NULL";
        for (const Label& l : kLabels) {
            if (key && std::strcmp(key, l.key) == 0) text = l.text;
        }
        ret<const char*>(text); break; }
    case GET_PLAYER_RADIO_STATION_INDEX: expect_argc(0, "GET_PLAYER_RADIO_STATION_INDEX");
        ret<int>(in_vehicle() && g_radio >= 0 ? g_radio : 255); break;
    case GET_PLAYER_RADIO_STATION_NAME: expect_argc(0, "GET_PLAYER_RADIO_STATION_NAME");
        ret<const char*>(in_vehicle() && g_radio >= 0 ? kStations[g_radio] : nullptr);
        break;
    case GET_NUM_UNLOCKED_RADIO_STATIONS: expect_argc(0, "GET_NUM_UNLOCKED_RADIO_STATIONS");
        ret<int>(kNumStations); break;
    case GET_RADIO_STATION_NAME: { expect_argc(1, "GET_RADIO_STATION_NAME");
        const int i = arg<int>(0);
        ret<const char*>(i >= 0 && i < kNumStations ? kStations[i] : nullptr);
        break; }
    case GET_ENTITY_COORDS: expect_argc(2, "GET_ENTITY_COORDS");
        ret_vec3(kPlayerX, kPlayerY, kPlayerZ); break;
    case GET_FIRST_BLIP_INFO_ID: expect_argc(1, "GET_FIRST_BLIP_INFO_ID");
        g_walk_sprite = arg<int>(0);
        g_walk_pos = 0;
        ret<int>(walk_next()); break;
    case GET_NEXT_BLIP_INFO_ID: expect_argc(1, "GET_NEXT_BLIP_INFO_ID");
        if (arg<int>(0) != g_walk_sprite) {
            std::fprintf(stderr, "[mock] NEXT_BLIP for sprite %d without FIRST\n",
                         arg<int>(0));
            std::fflush(stderr);
            ExitProcess(3);
        }
        ret<int>(walk_next()); break;
    case DOES_BLIP_EXIST: expect_argc(1, "DOES_BLIP_EXIST");
        ret_bool(map_blip(arg<int>(0)) != nullptr ||
                 (g_waypoint && arg<int>(0) == kWaypointBlip)); break;
    case GET_BLIP_INFO_ID_COORD: { expect_argc(1, "GET_BLIP_INFO_ID_COORD");
        const int id = arg<int>(0);
        if (id == kWaypointBlip && g_waypoint) { ret_vec3(g_wp_x, g_wp_y, 0.0f); break; }
        const MapBlip* m = map_blip(id);
        if (!m) {
            std::fprintf(stderr, "[mock] BLIP COORD for unknown blip %d\n", id);
            std::fflush(stderr);
            ExitProcess(3);
        }
        ret_vec3(m->x, m->y, m->z); break; }
    case GET_BLIP_INFO_ID_DISPLAY: expect_argc(1, "GET_BLIP_INFO_ID_DISPLAY"); ret<int>(2); break;
    case GET_BLIP_INFO_ID_TYPE: { expect_argc(1, "GET_BLIP_INFO_ID_TYPE");
        const MapBlip* m = map_blip(arg<int>(0));
        ret<int>(m ? m->type : 4); break; }
    case GET_BLIP_SPRITE: { expect_argc(1, "GET_BLIP_SPRITE");
        const MapBlip* m = map_blip(arg<int>(0));
        ret<int>(m ? m->sprite : kWaypointSprite); break; }
    case GET_BLIP_COLOUR: { expect_argc(1, "GET_BLIP_COLOUR");
        const MapBlip* m = map_blip(arg<int>(0));
        ret<int>(m ? m->colour : 0); break; }
    case GET_WAYPOINT_BLIP_ENUM_ID: expect_argc(0, "GET_WAYPOINT_BLIP_ENUM_ID");
        ret<int>(kWaypointSprite); break;
    case IS_WAYPOINT_ACTIVE: expect_argc(0, "IS_WAYPOINT_ACTIVE"); ret_bool(g_waypoint); break;
    case SET_NEW_WAYPOINT: expect_argc(2, "SET_NEW_WAYPOINT");
        g_waypoint = true;
        g_wp_x = arg<float>(0);
        g_wp_y = arg<float>(1);
        std::fprintf(stderr, "[mock] waypoint set at (%.0f, %.0f)\n", g_wp_x, g_wp_y);
        std::fflush(stderr);
        break;
    case SET_WAYPOINT_OFF: expect_argc(0, "SET_WAYPOINT_OFF");
        g_waypoint = false;
        std::fprintf(stderr, "[mock] waypoint off\n");
        std::fflush(stderr);
        break;
    case SET_RADIO_TO_STATION_NAME: { expect_argc(1, "SET_RADIO_TO_STATION_NAME");
        const char* name = arg<const char*>(0);
        g_radio = -1;
        for (int i = 0; i < kNumStations; ++i) {
            if (name && std::strcmp(name, kStations[i]) == 0) g_radio = i;
        }
        break; }

    // ---- cheats natives --------------------------------------------
    case SET_ENTITY_HEALTH: { expect_argc(4, "SET_ENTITY_HEALTH");
        if (arg<int>(0) != kPed) {
            std::fprintf(stderr, "[mock] SET_ENTITY_HEALTH on non-ped %d\n", arg<int>(0));
            ExitProcess(3);
        }
        g_mock_health = arg<int>(1); break; }
    case SET_PED_ARMOUR: expect_argc(2, "SET_PED_ARMOUR");
        g_mock_armor = arg<int>(1); break;
    case SET_PLAYER_WANTED_LEVEL: expect_argc(3, "SET_PLAYER_WANTED_LEVEL");
        g_mock_wanted = arg<int>(1); break;
    case SET_PLAYER_WANTED_LEVEL_NOW: expect_argc(2, "SET_PLAYER_WANTED_LEVEL_NOW");
        ++g_wanted_apply_calls; break;
    case CLEAR_PLAYER_WANTED_LEVEL: expect_argc(1, "CLEAR_PLAYER_WANTED_LEVEL");
        g_mock_wanted = 0; break;
    case GIVE_WEAPON_TO_PED: { expect_argc(5, "GIVE_WEAPON_TO_PED");
        if (arg<int>(0) != kPed) {
            std::fprintf(stderr, "[mock] GIVE_WEAPON_TO_PED on non-ped %d\n", arg<int>(0));
            ExitProcess(3);
        }
        ++g_given_count; break; }
    case SET_PED_AMMO: expect_argc(4, "SET_PED_AMMO");
        ++g_ammo_set_count; break;
    case GET_MAX_AMMO: { expect_argc(3, "GET_MAX_AMMO");
        int* out = arg<int*>(2);
        if (out) *out = 999;
        ret_bool(true); break; }
    case SET_PED_INFINITE_AMMO_CLIP: expect_argc(2, "SET_PED_INFINITE_AMMO_CLIP");
        g_infinite_clip = arg<int>(1) != 0; break;
    case SET_VEHICLE_FIXED: expect_argc(1, "SET_VEHICLE_FIXED");
        ++g_fix_calls;
        g_veh_engine_hp = 1000;
        g_veh_body_hp = 1000; break;
    case SET_VEHICLE_ENGINE_HEALTH: expect_argc(2, "SET_VEHICLE_ENGINE_HEALTH");
        g_veh_engine_hp = static_cast<int32_t>(arg<float>(1)); break;
    case SET_VEHICLE_BODY_HEALTH: expect_argc(2, "SET_VEHICLE_BODY_HEALTH");
        g_veh_body_hp = static_cast<int32_t>(arg<float>(1)); break;
    case SET_VEHICLE_DIRT_LEVEL: expect_argc(2, "SET_VEHICLE_DIRT_LEVEL");
        g_veh_dirt = arg<float>(1);
        ++g_dirt_calls; break;
    case SET_WEATHER_TYPE_NOW_PERSIST: { expect_argc(1, "SET_WEATHER_TYPE_NOW_PERSIST");
        const char* name = arg<const char*>(0);
        if (name) {
            std::snprintf(g_weather_cur, sizeof(g_weather_cur), "%s", name);
        }
        ++g_weather_calls; break; }
    case CLEAR_WEATHER_TYPE_PERSIST: expect_argc(0, "CLEAR_WEATHER_TYPE_PERSIST");
        g_weather_cur[0] = '\0';
        ++g_weather_cleared; break;
    case GET_CLOCK_HOURS: expect_argc(0, "GET_CLOCK_HOURS");
        ret<int>(g_clock_h); break;
    case SET_CLOCK_TIME: expect_argc(3, "SET_CLOCK_TIME");
        g_clock_h = arg<int>(0);
        g_clock_m = arg<int>(1);
        ++g_clock_set_calls; break;
    case SPECIAL_ABILITY_FILL_METER: expect_argc(3, "SPECIAL_ABILITY_FILL_METER");
        ++g_special_fills; break;
    case IS_MODEL_IN_CDIMAGE: expect_argc(1, "IS_MODEL_IN_CDIMAGE");
        ret_bool(arg<uint32_t>(0) == joaat("adder") ||
                 arg<uint32_t>(0) == joaat("zentorno") ||
                 arg<uint32_t>(0) == joaat("bati") ||
                 arg<uint32_t>(0) == joaat("sanchez") ||
                 arg<uint32_t>(0) == joaat("frogger") ||
                 arg<uint32_t>(0) == joaat("bmx")); break;
    case IS_MODEL_A_VEHICLE: expect_argc(1, "IS_MODEL_A_VEHICLE");
        // Every CDIMAGE hit here is a vehicle; model-is-a-vehicle wins
        // on all curated spawn models.
        ret_bool(true); break;
    case REQUEST_MODEL: expect_argc(1, "REQUEST_MODEL");
        g_pending_model = arg<uint32_t>(0);
        g_model_poll = 0; break;
    case HAS_MODEL_LOADED: expect_argc(1, "HAS_MODEL_LOADED");
        if (arg<uint32_t>(0) != g_pending_model) {
            ret_bool(false);
        } else {
            ret_bool(++g_model_poll > kSpawnLoadFrames);
        }
        break;
    case SET_MODEL_AS_NO_LONGER_NEEDED: expect_argc(1, "SET_MODEL_AS_NO_LONGER_NEEDED");
        g_pending_model = 0; break;
    case GET_ENTITY_HEADING: expect_argc(1, "GET_ENTITY_HEADING");
        ret<float>(0.0f); break;
    case CREATE_VEHICLE: { expect_argc(8, "CREATE_VEHICLE");
        g_spawn_last_veh = kVeh;   // reuse the car handle from story mock
        ret<int>(g_spawn_last_veh); break; }
    case SET_VEHICLE_ON_GROUND_PROPERLY: expect_argc(2, "SET_VEHICLE_ON_GROUND_PROPERLY");
        ret_bool(true); break;
    case SET_PED_INTO_VEHICLE: expect_argc(3, "SET_PED_INTO_VEHICLE"); break;
    case SET_VEHICLE_AS_NO_LONGER_NEEDED: expect_argc(1, "SET_VEHICLE_AS_NO_LONGER_NEEDED");
        g_spawn_released = true; break;
    case SET_PLAYER_INVINCIBLE_BUT_HAS_REACTIONS: expect_argc(2, "SET_PLAYER_INVINCIBLE_BUT_HAS_REACTIONS");
        g_invincible = arg<int>(1) != 0; break;
    case SET_RUN_SPRINT_MULTIPLIER_FOR_PLAYER: expect_argc(2, "SET_RUN_SPRINT_MULTIPLIER_FOR_PLAYER");
        g_run_mult = arg<float>(1); break;
    case SET_GRAVITY_LEVEL: expect_argc(1, "SET_GRAVITY_LEVEL");
        g_gravity = arg<int>(0); break;
    case SET_TIME_SCALE: expect_argc(1, "SET_TIME_SCALE");
        g_time_scale = arg<float>(0); break;
    case SET_POLICE_IGNORE_PLAYER: expect_argc(2, "SET_POLICE_IGNORE_PLAYER");
        g_police_ignore = arg<int>(1) != 0; break;
    case STAT_SET_CHEAT_IS_ACTIVE: expect_argc(0, "STAT_SET_CHEAT_IS_ACTIVE");
        ++g_cheat_stat_set; break;

    // ---- phase-2 cheats --------------------------------------------
    case SET_PED_IS_DRUNK: expect_argc(2, "SET_PED_IS_DRUNK"); break;
    case SET_SWIM_MULTIPLIER_FOR_PLAYER: expect_argc(2, "SET_SWIM_MULTIPLIER_FOR_PLAYER");
        break;
    case SET_SUPER_JUMP_THIS_FRAME: expect_argc(1, "SET_SUPER_JUMP_THIS_FRAME"); break;
    case SET_EXPLOSIVE_AMMO_THIS_FRAME: expect_argc(1, "SET_EXPLOSIVE_AMMO_THIS_FRAME");
        break;
    case SET_EXPLOSIVE_MELEE_THIS_FRAME: expect_argc(1, "SET_EXPLOSIVE_MELEE_THIS_FRAME");
        break;
    case SET_FIRE_AMMO_THIS_FRAME: expect_argc(1, "SET_FIRE_AMMO_THIS_FRAME"); break;
    case SET_VEHICLE_REDUCE_GRIP: expect_argc(2, "SET_VEHICLE_REDUCE_GRIP"); break;
    case SET_ENTITY_COORDS: expect_argc(8, "SET_ENTITY_COORDS"); break;

    default:
        // The real Script Hook V treats an unknown native as fatal; so
        // does the mock, so a typo'd hash fails the test loudly.
        std::fprintf(stderr, "[mock] UNKNOWN NATIVE 0x%016llX\n",
                     static_cast<unsigned long long>(g_hash));
        std::fflush(stderr);
        ExitProcess(2);
    }
}

DWORD WINAPI script_thread(LPVOID arg) {
    // The real thing starts scripts once the game is up, well after
    // DllMain returns.
    Sleep(300);
    ScriptMain fn;
    std::memcpy(&fn, &arg, sizeof(fn));
    fn();
    return 0;
}

}  // namespace

extern "C" {

void mock_scriptRegister(HMODULE /*module*/, ScriptMain fn) {
    g_t0 = GetTickCount64();
    char buf[32] = {0};
    if (GetEnvironmentVariableA("MOCK_SCENARIO", buf, sizeof(buf)) > 0) {
        if (!std::strcmp(buf, "online"))  g_scenario = kOnline;
        if (!std::strcmp(buf, "loading")) g_scenario = kLoading;
        if (!std::strcmp(buf, "other"))   g_scenario = kOther;
        if (!std::strcmp(buf, "stall"))   g_scenario = kStall;
        if (!std::strcmp(buf, "wheel"))   g_scenario = kWheel;
        if (!std::strcmp(buf, "blips"))   g_scenario = kBlips;
        if (!std::strcmp(buf, "cheats"))  g_scenario = kCheats;
    }
    std::fprintf(stderr, "[mock] scriptRegister, scenario=%d\n", g_scenario);
    std::fflush(stderr);
    LPVOID arg;
    std::memcpy(&arg, &fn, sizeof(arg));
    CloseHandle(CreateThread(NULL, 0, script_thread, arg, 0, NULL));
}

void mock_scriptUnregister(HMODULE /*module*/) {}
void mock_scriptUnregisterFn(ScriptMain /*fn*/) {}

void mock_scriptWait(DWORD ms) {
    if (g_scenario == kStall && elapsed_ms() > 3000) {
        Sleep(INFINITE);   // the game stopped resuming script fibers
    }
    wheel_step();
    blips_step();
    Sleep(ms < 16 ? 16 : ms);   // ~60 fps
}

void mock_nativeInit(UINT64 hash) {
    g_hash = hash;
    g_argc = 0;
}

void mock_nativePush64(UINT64 val) {
    if (g_argc < 32) g_args[g_argc++] = val;
}

PUINT64 mock_nativeCall() {
    ++g_calls;
    dispatch();
    return reinterpret_cast<PUINT64>(g_ret);
}

int mock_getGameVersion() { return 9999; }

}  // extern "C"

extern "C" BOOL WINAPI DllMain(HINSTANCE, DWORD, LPVOID) { return TRUE; }
