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

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "../src/joaat.h"
#include "../src/natives.h"

namespace {

using ScriptMain = void (*)();

enum Scenario { kStory, kOnline, kLoading, kOther, kStall, kWheel };
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

uint64_t elapsed_ms() { return GetTickCount64() - g_t0; }

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
    case GET_ENTITY_HEALTH: expect_argc(1, "GET_ENTITY_HEALTH"); ret<int>(175); break;
    case GET_ENTITY_MAX_HEALTH: expect_argc(1, "GET_ENTITY_MAX_HEALTH"); ret<int>(200); break;
    case GET_PED_ARMOUR: expect_argc(1, "GET_PED_ARMOUR"); ret<int>(40); break;
    case GET_PLAYER_MAX_ARMOUR: expect_argc(1, "GET_PLAYER_MAX_ARMOUR"); ret<int>(100); break;
    case GET_PLAYER_WANTED_LEVEL: expect_argc(1, "GET_PLAYER_WANTED_LEVEL"); ret<int>(3); break;
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
    case IS_PED_IN_ANY_VEHICLE: expect_argc(2, "IS_PED_IN_ANY_VEHICLE");
        ret_bool(in_vehicle()); break;
    case GET_VEHICLE_PED_IS_IN: expect_argc(2, "GET_VEHICLE_PED_IS_IN");
        ret<int>(in_vehicle() ? kVeh : 0); break;
    case GET_VEHICLE_CLASS: expect_argc(1, "GET_VEHICLE_CLASS"); ret<int>(7); break;
    case GET_ENTITY_SPEED: expect_argc(1, "GET_ENTITY_SPEED"); ret<float>(31.5f); break;
    case GET_VEHICLE_ENGINE_HEALTH: expect_argc(1, "GET_VEHICLE_ENGINE_HEALTH");
        ret<float>(870.0f); break;
    case GET_VEHICLE_BODY_HEALTH: expect_argc(1, "GET_VEHICLE_BODY_HEALTH");
        ret<float>(990.0f); break;
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
    case SET_RADIO_TO_STATION_NAME: { expect_argc(1, "SET_RADIO_TO_STATION_NAME");
        const char* name = arg<const char*>(0);
        g_radio = -1;
        for (int i = 0; i < kNumStations; ++i) {
            if (name && std::strcmp(name, kStations[i]) == 0) g_radio = i;
        }
        break; }
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
