// Per-frame GTA V state reader.
//
// Lifecycle:
//   DllMain binds Script Hook V (shv::load) and registers script::main.
//   Script Hook V starts main() on a script fiber once the game's
//   script engine is up, then resumes it after each
//   shv::script_wait(kTickIntervalMs) (25 Hz).
//
// Per tick:
//   * GTA Online session -> publish {online} and touch nothing else.
//   * No player ped / loading screen -> publish {in_game=false}.
//   * Otherwise read character, stats, special ability, weapon,
//     vehicle, radio, the weapon-wheel control and the map waypoint,
//     publish, then run queued client commands.
//   * Owned weapons (~1 Hz) and the radio station list (~0.2 Hz) are
//     scanned on a slower clock and published as versioned lists; the
//     map blips are walked a slice per tick (blips.cpp).
//
// What happens while the game is paused is deliberately not assumed:
// if the fiber keeps ticking, `paused` comes from IS_PAUSE_MENU_ACTIVE;
// if the game stops resuming script fibers, publishing simply stops and
// the bridge reports the sample as `stalled`. The HUD treats both the
// same way.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

#include "blips.h"
#include "cheats.h"
#include "commands.h"
#include "joaat.h"
#include "log.h"
#include "natives.h"
#include "script.h"
#include "shv.h"
#include "snapshot.h"
#include "weapons.h"

static_assert(weapons::kCount <= snapshot::kMaxWeapons,
              "owned-weapon list cannot hold the whole weapon table");

namespace {

// The bridge sends state at 20 Hz, so sampling every rendered frame
// would be wasted work — and each native call costs more than usual
// when the game is running under x86 emulation on an ARM handheld.
constexpr DWORD    kTickIntervalMs = 40;     // 25 Hz
constexpr uint64_t kWeaponScanMs  = 1000;
constexpr uint64_t kStationScanMs = 5000;
constexpr int32_t  kRadioOffIndex = 255;
// INPUT_SELECT_WEAPON has to stay down this long before `wheel` is
// reported. A quick tap is the game's holster shortcut and must not
// flash the companion's wheel. Tune on-device against how soon the
// game's own wheel appears.
constexpr uint64_t kWheelHoldMs   = 120;

constexpr uint32_t kModelMichael  = joaat("player_zero");
constexpr uint32_t kModelFranklin = joaat("player_one");
constexpr uint32_t kModelTrevor   = joaat("player_two");

// Story-mode cash is a per-character stat, not a ped property.
constexpr uint32_t kCashStat[3] = {
    joaat("SP0_TOTAL_CASH"),   // Michael
    joaat("SP1_TOTAL_CASH"),   // Franklin
    joaat("SP2_TOTAL_CASH"),   // Trevor
};

volatile LONG g_started = 0;

uint64_t g_next_weapon_scan = 0;
uint64_t g_next_station_scan = 0;
uint64_t g_select_since = 0;       // tick the wheel control went down, 0 = up
bool     g_wheel_logged = false;   // this hold already has its log line
int8_t   g_last_character = snapshot::kCharOther;
bool     g_logged_first_in_game = false;
// Latches whether the previous tick reached the in-game tail. On the
// edge back to not-in-game (online, loading, menu-to-shell) we roll
// world natives (time scale, weather, gravity) back once via
// cheats::reset_world so the shell behaves normally; the mask is
// kept and cheats::tick re-applies on the next in-game tick.
bool     g_cheats_in_game_last = false;

// Vehicle display-name cache: the label lookup is two string natives,
// pointless to repeat 60x a second for the same car.
uint32_t g_cached_vehicle_model = 0;
char     g_cached_vehicle_name[snapshot::kLabelLen] = {0};

// Bounded copy of a game-owned C string. Tolerates nullptr.
void copy_str(char* dst, size_t cap, const char* src) {
    if (cap == 0) return;
    size_t i = 0;
    if (src) {
        for (; i + 1 < cap && src[i]; ++i) dst[i] = src[i];
    }
    dst[i] = '\0';
}

// Localised text for a GXT key. Leaves `out` empty when the game has
// no entry (it answers the literal string "NULL").
void label_for(const char* key, char* out, size_t cap) {
    out[0] = '\0';
    if (!key || !key[0]) return;
    const char* text = shv::call<const char*>(natives::GET_LABEL_TEXT, key);
    if (!text || !text[0] || std::strcmp(text, "NULL") == 0) return;
    copy_str(out, cap, text);
}

int8_t character_of(uint32_t model) {
    if (model == kModelMichael)  return snapshot::kCharMichael;
    if (model == kModelFranklin) return snapshot::kCharFranklin;
    if (model == kModelTrevor)   return snapshot::kCharTrevor;
    return snapshot::kCharOther;
}

void read_stats(snapshot::State& s, int32_t player, int32_t ped) {
    s.health     = shv::call<int32_t>(natives::GET_ENTITY_HEALTH, ped);
    s.max_health = shv::call<int32_t>(natives::GET_ENTITY_MAX_HEALTH, ped);
    s.armor      = shv::call<int32_t>(natives::GET_PED_ARMOUR, ped);
    s.max_armor  = shv::call<int32_t>(natives::GET_PLAYER_MAX_ARMOUR, player);

    int32_t wanted = shv::call<int32_t>(natives::GET_PLAYER_WANTED_LEVEL, player);
    if (wanted < 0) wanted = 0;
    if (wanted > 5) wanted = 5;
    s.wanted = static_cast<uint8_t>(wanted);

    if (s.character >= 0) {
        // Out-params are script slots (8 bytes); give the native a full
        // slot to write into even though the value is a 32-bit int.
        alignas(8) int64_t out = 0;
        if (shv::call_bool(natives::STAT_GET_INT, kCashStat[s.character],
                           &out, -1)) {
            s.money = static_cast<int32_t>(out);
            s.money_valid = true;
        }
        s.special_unlocked =
            shv::call_bool(natives::IS_SPECIAL_ABILITY_UNLOCKED, s.ped_model);
        s.special_enabled =
            shv::call_bool(natives::IS_SPECIAL_ABILITY_ENABLED, player, 0);
        s.special_active =
            shv::call_bool(natives::IS_SPECIAL_ABILITY_ACTIVE, player, 0);
        s.special_full =
            shv::call_bool(natives::IS_SPECIAL_ABILITY_METER_FULL, player, 0);
    }
}

void read_weapon(snapshot::State& s, int32_t ped) {
    s.weapon = shv::call<uint32_t>(natives::GET_SELECTED_PED_WEAPON, ped);
    copy_str(s.weapon_name, sizeof(s.weapon_name), weapons::name_of(s.weapon));
    if (s.weapon == 0 || s.weapon == weapons::kUnarmed) return;

    s.weapon_group =
        shv::call<uint32_t>(natives::GET_WEAPONTYPE_GROUP, s.weapon);
    s.ammo_total =
        shv::call<int32_t>(natives::GET_AMMO_IN_PED_WEAPON, ped, s.weapon);
    alignas(8) int64_t clip = 0;
    if (shv::call_bool(natives::GET_AMMO_IN_CLIP, ped, s.weapon, &clip)) {
        s.ammo_clip = static_cast<int32_t>(clip);
    }
    s.clip_size =
        shv::call<int32_t>(natives::GET_MAX_AMMO_IN_CLIP, ped, s.weapon, 1);
}

void read_vehicle(snapshot::State& s, int32_t ped) {
    if (!shv::call_bool(natives::IS_PED_IN_ANY_VEHICLE, ped, 0)) return;
    const int32_t veh =
        shv::call<int32_t>(natives::GET_VEHICLE_PED_IS_IN, ped, 0);
    if (veh == 0 || !shv::call_bool(natives::DOES_ENTITY_EXIST, veh)) return;

    s.in_vehicle    = true;
    s.vehicle_model = shv::call<uint32_t>(natives::GET_ENTITY_MODEL, veh);
    s.vehicle_class = shv::call<int32_t>(natives::GET_VEHICLE_CLASS, veh);
    s.vehicle_speed_mps = shv::call<float>(natives::GET_ENTITY_SPEED, veh);
    s.vehicle_engine_hp =
        shv::call<float>(natives::GET_VEHICLE_ENGINE_HEALTH, veh);
    s.vehicle_body_hp =
        shv::call<float>(natives::GET_VEHICLE_BODY_HEALTH, veh);
    if (s.vehicle_class == natives::kClassHelicopters ||
        s.vehicle_class == natives::kClassPlanes) {
        s.vehicle_altitude_m =
            shv::call<float>(natives::GET_ENTITY_HEIGHT_ABOVE_GROUND, veh);
    }
    s.vehicle_has_radio = shv::call_bool(natives::DOES_PLAYER_VEH_HAVE_RADIO);

    if (s.vehicle_model != g_cached_vehicle_model) {
        const char* key = shv::call<const char*>(
            natives::GET_DISPLAY_NAME_FROM_VEHICLE_MODEL, s.vehicle_model);
        label_for(key, g_cached_vehicle_name, sizeof(g_cached_vehicle_name));
        if (!g_cached_vehicle_name[0]) {
            // No localised entry: fall back to the raw key ("ADDER").
            copy_str(g_cached_vehicle_name, sizeof(g_cached_vehicle_name), key);
        }
        g_cached_vehicle_model = s.vehicle_model;
    }
    copy_str(s.vehicle_name, sizeof(s.vehicle_name), g_cached_vehicle_name);
}

void read_radio(snapshot::State& s) {
    s.radio_index = -1;
    s.radio_name[0] = '\0';
    // The game remembers the last-tuned station even on foot, but the
    // HUD and spec treat the player as having no active radio outside
    // a vehicle. Short-circuit here so the stream matches.
    if (!s.in_vehicle) return;
    const int32_t idx =
        shv::call<int32_t>(natives::GET_PLAYER_RADIO_STATION_INDEX);
    if (idx < 0 || idx >= kRadioOffIndex) return;
    s.radio_index = idx;
    copy_str(s.radio_name, sizeof(s.radio_name),
             shv::call<const char*>(natives::GET_PLAYER_RADIO_STATION_NAME));
}

// Dpad-down toggle disabled on GTA V: no control id gives a stable
// physical-dpad-DOWN press across on-foot and in-vehicle contexts.
// INPUT_CELLPHONE_DOWN (174) is clean on-foot but aliases to dpad-
// LEFT in a vehicle (radio-prev); INPUT_SCRIPT_PAD_* is consumed by
// the game before any script sees it. The V companion uses the pin
// icon in the status bar instead. The IV plugin still ships the
// feature because IS_BUTTON_PRESSED(0, 9) is context-stable on IV.
void read_dpad_down(snapshot::State& s) {
    s.dpad_down_edge = false;
}

// Weapon-wheel control. Needs the session flags and in_vehicle already
// filled in. On foot only for now: commands:: refuses set_weapon in a
// vehicle, so showing a wheel there would only offer taps that fail.
void read_wheel(snapshot::State& s, uint64_t now) {
    const bool usable = !s.in_vehicle && !s.paused && !s.switching &&
                        !s.cutscene && !s.animal;
    s.select_held = usable &&
        shv::call_bool(natives::IS_CONTROL_PRESSED,
                       static_cast<int32_t>(natives::kPlayerControl),
                       static_cast<int32_t>(natives::kInputSelectWeapon));
    if (!s.select_held) {
        g_select_since = 0;
        g_wheel_logged = false;
        return;
    }
    if (g_select_since == 0) {
        g_select_since = now;
        // The companion is about to draw the loadout: make sure the
        // list (and its ammo counts) is this tick's, not up to 1 s old.
        g_next_weapon_scan = 0;
    }
    s.wheel = now - g_select_since >= kWheelHoldMs;
    if (s.wheel && !g_wheel_logged) {
        // One line per hold: on a device this is the only way to tell
        // "the control is never seen" from "the companion ignores it".
        g_wheel_logged = true;
        thor_log("[script] weapon-wheel control held -> wheel on");
    }
}

void scan_weapons(int32_t ped) {
    static snapshot::Weapon list[snapshot::kMaxWeapons];
    uint32_t n = 0;
    for (size_t i = 0; i < weapons::kCount; ++i) {
        const uint32_t hash = weapons::kTable[i].hash;
        if (!shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, hash, 0)) continue;
        snapshot::Weapon& w = list[n++];
        std::memset(&w, 0, sizeof(w));   // memcmp-stable padding
        w.hash  = hash;
        w.group = shv::call<uint32_t>(natives::GET_WEAPONTYPE_GROUP, hash);
        w.ammo  = shv::call<int32_t>(natives::GET_AMMO_IN_PED_WEAPON, ped, hash);
        copy_str(w.name, sizeof(w.name), weapons::kTable[i].name);
    }
    snapshot::publish_weapons(list, n);
}

void scan_stations() {
    static snapshot::Station list[snapshot::kMaxStations];
    int32_t count = shv::call<int32_t>(natives::GET_NUM_UNLOCKED_RADIO_STATIONS);
    if (count < 0) count = 0;
    if (count > static_cast<int32_t>(snapshot::kMaxStations)) {
        count = static_cast<int32_t>(snapshot::kMaxStations);
    }
    uint32_t n = 0;
    for (int32_t i = 0; i < count; ++i) {
        const char* name =
            shv::call<const char*>(natives::GET_RADIO_STATION_NAME, i);
        if (!name || !name[0]) continue;
        snapshot::Station& st = list[n++];
        std::memset(&st, 0, sizeof(st));
        copy_str(st.name, sizeof(st.name), name);
        label_for(st.name, st.label, sizeof(st.label));
    }
    snapshot::publish_stations(list, n);
}

void tick() {
    const uint64_t now = GetTickCount64();
    snapshot::State s{};
    s.ts_ms = now;
    s.character = snapshot::kCharOther;
    s.radio_index = -1;

    // Story mode only. Script Hook V itself exits the game on entering
    // GTA Online; this is a second, independent guard so that nothing
    // below ever runs against an online session.
    if (shv::call_bool(natives::NETWORK_IS_SESSION_STARTED)) {
        s.online = true;
        s.cheats_mask = cheats::mask();
        if (g_cheats_in_game_last) {
            cheats::reset_world();
            g_cheats_in_game_last = false;
        }
        snapshot::publish(s);
        commands::reject_all("blocked: online");
        return;
    }

    const int32_t player = shv::call<int32_t>(natives::PLAYER_ID);
    const int32_t ped    = shv::call<int32_t>(natives::PLAYER_PED_ID);
    if (ped == 0 ||
        !shv::call_bool(natives::DOES_ENTITY_EXIST, ped) ||
        shv::call_bool(natives::GET_IS_LOADING_SCREEN_ACTIVE)) {
        s.cheats_mask = cheats::mask();
        if (g_cheats_in_game_last) {
            cheats::reset_world();
            g_cheats_in_game_last = false;
        }
        snapshot::publish(s);   // in_game = false
        commands::reject_all("blocked: not in game");
        return;
    }

    s.in_game   = true;
    s.paused    = shv::call_bool(natives::IS_PAUSE_MENU_ACTIVE);
    s.switching = shv::call_bool(natives::IS_PLAYER_SWITCH_IN_PROGRESS);
    s.cutscene  = shv::call_bool(natives::IS_CUTSCENE_PLAYING);
    s.mission   = shv::call_bool(natives::GET_MISSION_FLAG);
    s.animal    = shv::call_bool(natives::GET_IS_PLAYER_IN_ANIMAL_FORM);

    s.ped_model = shv::call<uint32_t>(natives::GET_ENTITY_MODEL, ped);
    s.character = character_of(s.ped_model);

    read_stats(s, player, ped);
    read_weapon(s, ped);
    read_vehicle(s, ped);
    read_radio(s);
    read_wheel(s, now);
    read_dpad_down(s);
    blips::tick(ped, s);

    // A character switch swaps the whole loadout; rescan immediately
    // rather than showing the previous character's weapons for a second.
    if (s.character != g_last_character) {
        thor_log("[script] character -> %d (model 0x%08X)",
                 static_cast<int>(s.character), s.ped_model);
        g_last_character = s.character;
        g_next_weapon_scan = 0;
    }
    if (now >= g_next_weapon_scan) {
        scan_weapons(ped);
        g_next_weapon_scan = now + kWeaponScanMs;
    }
    if (now >= g_next_station_scan) {
        scan_stations();
        g_next_station_scan = now + kStationScanMs;
    }

    s.cheats_mask = cheats::mask();
    g_cheats_in_game_last = true;

    snapshot::publish(s);

    if (!g_logged_first_in_game) {
        g_logged_first_in_game = true;
        thor_log("[script] first in-game sample: character=%d health=%d/%d "
                 "armor=%d wanted=%u weapon=0x%08X in_vehicle=%d",
                 static_cast<int>(s.character), s.health, s.max_health,
                 s.armor, static_cast<unsigned>(s.wanted), s.weapon,
                 s.in_vehicle ? 1 : 0);
    }

    commands::drain(player, ped, s);
    // After drain so a spawn-car tap this tick already has the model
    // request issued; advance_spawn() will not resolve it on this same
    // tick (HAS_MODEL_LOADED needs at least one game frame).
    cheats::tick(player, ped);
}

}  // namespace

namespace script {

void main() {
    InterlockedExchange(&g_started, 1);
    thor_log("[script] fiber started (Script Hook V game_version=%d)",
             shv::game_version());
    for (;;) {
        tick();
        shv::script_wait(kTickIntervalMs);
    }
}

bool started() {
    return InterlockedCompareExchange(&g_started, 1, 1) == 1;
}

}  // namespace script
