// Host-side test for src/cheats.cpp — the dispatch of cheat ids to
// game-state writes. Built with the machine's own C++ compiler. The
// game is replaced by fakes for every game::* function cheats.cpp
// calls, each of which enforces the contract the real natives are
// believed to have (sane handles, bounded ammo, hour/minute inside
// range, etc.) and records what was written so the tests can inspect
// it.
//
// What this proves: the right natives are called for each cheat id,
// with the right arguments and in the right order; the toggle mask
// tracks the real on/off state; the weather + clock cycles step and
// wrap correctly; reset_world rolls the world natives back without
// touching the mask; a ped-handle change re-applies the active
// toggles. What it cannot prove: the hashes resolve on-device, the
// natives really do what their names say, or that FIX_CAR takes a
// vehicle in the shape cheat_natives.cpp marshals.

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../src/cheat_natives.h"
#include "../src/cheats.h"
#include "../src/weapons.h"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool ok, const char* what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("  FAIL %s\n", what);
    }
}

// ---- fake game -------------------------------------------------------

constexpr uint32_t kPlayer = 0x7;
constexpr uint32_t kPed    = 0x1001;
constexpr uint32_t kVeh    = 0x2002;

struct State {
    uint32_t player_id = kPlayer;
    uint32_t ped       = kPed;
    uint32_t veh       = kVeh;      // what get_car_char_is_using returns
    uint32_t max_wanted = 6;

    uint32_t health = 100;
    uint32_t armour = 0;

    uint32_t wanted_level = 0;
    int      wanted_applied = 0;    // times apply_wanted_level_change_now fired

    std::vector<std::pair<uint32_t, uint32_t>> given;  // (weapon, ammo)
    std::vector<std::pair<uint32_t, uint32_t>> ammo_set;

    int fix_calls = 0;
    int wash_calls = 0;

    uint32_t forced_weather = 0xFFFFFFFFu;
    int      forced_calls = 0;
    int      release_weather_calls = 0;

    uint32_t clock_h = 7, clock_m = 30;

    bool  invincible = false;
    bool  fast_reload = false;
    bool  never_tired = false;
    bool  police_ignore = false;
    float time_scale = 1.0f;

    void reset() { *this = State{}; }
} g;

void fake_reset() {
    g.reset();
    cheats::reset();
    weapons::reset();
}

snapshot::Player on_foot() {
    snapshot::Player p{};
    p.in_game = true;
    p.wanted_level = static_cast<uint8_t>(g.wanted_level);
    return p;
}

snapshot::Player in_vehicle() {
    snapshot::Player p = on_foot();
    p.in_vehicle = true;
    return p;
}

}  // namespace

namespace game {

// identity
uint32_t get_player_id() { return g.player_id; }
uint32_t get_car_char_is_using(uint32_t ped) {
    check(ped == g.ped, "veh native: called with the player ped");
    return g.veh;
}

// stats
void set_char_health(uint32_t ped, uint32_t health) {
    check(ped == g.ped, "health native: called with the player ped");
    g.health = health;
}
void add_armour_to_char(uint32_t ped, uint32_t amount) {
    check(ped == g.ped, "armour native: called with the player ped");
    g.armour = amount;
}

// wanted
uint32_t get_max_wanted_level() { return g.max_wanted; }
void alter_wanted_level(uint32_t player, uint32_t level) {
    check(player == g.player_id, "wanted native: called with the player id");
    g.wanted_level = level;
}
void apply_wanted_level_change_now(uint32_t player) {
    check(player == g.player_id, "apply-wanted: called with the player id");
    ++g.wanted_applied;
}
void clear_wanted_level(uint32_t player) {
    check(player == g.player_id, "clear-wanted: called with the player id");
    g.wanted_level = 0;
}

// loadout
void give_weapon_to_char(uint32_t ped, uint32_t weapon, uint32_t ammo) {
    check(ped == g.ped, "give-weapon: called with the player ped");
    check(weapon != 0, "give-weapon: never weapon 0");
    check(weapon != 8, "give-weapon: never weapon 8 (empty slot)");
    check(weapon < 100, "give-weapon: sane id");
    g.given.push_back({ weapon, ammo });
}
void set_char_ammo(uint32_t ped, uint32_t weapon, uint32_t ammo) {
    check(ped == g.ped, "set-ammo: called with the player ped");
    check(weapon != 0, "set-ammo: never weapon 0");
    check(weapon < 200, "set-ammo: sane id");
    g.ammo_set.push_back({ weapon, ammo });
}

// vehicle
void fix_car(uint32_t veh) {
    check(veh == g.veh, "fix-car: called with the player's vehicle");
    ++g.fix_calls;
}
void wash_vehicle_textures(uint32_t veh, uint32_t intensity) {
    check(veh == g.veh, "wash: called with the player's vehicle");
    check(intensity == 0, "wash: intensity 0 means fully clean");
    ++g.wash_calls;
}

// world
void force_weather_now(uint32_t weather) {
    check(weather < 20, "force-weather: id in a sane range");
    g.forced_weather = weather;
    ++g.forced_calls;
}
void release_weather() { ++g.release_weather_calls; }
void get_time_of_day(uint32_t* hour, uint32_t* minute) {
    *hour = g.clock_h;
    *minute = g.clock_m;
}
void set_time_of_day(uint32_t hour, uint32_t minute) {
    check(hour < 24 && minute < 60, "set-time: hour/minute in range");
    g.clock_h = hour;
    g.clock_m = minute;
}

// toggles
void set_player_invincible(uint32_t player, bool on) {
    check(player == g.player_id, "invincible: called with the player id");
    g.invincible = on;
}
void set_player_fast_reload(uint32_t player, bool on) {
    check(player == g.player_id, "fast-reload: called with the player id");
    g.fast_reload = on;
}
void set_player_never_gets_tired(uint32_t player, bool on) {
    check(player == g.player_id, "never-tired: called with the player id");
    g.never_tired = on;
}
void set_time_scale(float scale) { g.time_scale = scale; }
void set_police_ignore_player(uint32_t player, bool on) {
    check(player == g.player_id, "police-ignore: called with the player id");
    g.police_ignore = on;
}

// weapon_natives.h — unused in this test, but weapons::force_rescan()
// is a tiny flag-flip inside weapons.cpp and does not call these. We
// still have to link against weapons.cpp (for force_rescan), which has
// its own natives references in `scan()` — but scan() is not reached
// from this test's code paths (we never call read_owned). So linker
// weak symbols... actually, we DO link weapons.cpp, so these have to
// exist. Provide no-ops.
void get_char_weapon_in_slot(uint32_t, uint32_t, uint32_t*, uint32_t*,
                             uint32_t*) {}
uint32_t get_ammo_in_char_weapon(uint32_t, uint32_t) { return 0; }
bool is_button_pressed(uint32_t, uint32_t) { return false; }

}  // namespace game

void thor_log(const char* /*fmt*/, ...) {}

// ---- tests -----------------------------------------------------------

namespace {

void test_heal() {
    std::printf("heal\n");
    fake_reset();
    snapshot::Player p = on_foot();
    const char* err = cheats::run(cheats::kHeal, kPlayer, kPed, p);
    check(err == nullptr, "heal: ok");
    check(g.health == cheats::kMaxHealth, "heal: health = 200");
    check(g.armour == cheats::kMaxArmour, "heal: armour += 100");
}

void test_wanted() {
    std::printf("wanted +1 / clear\n");
    fake_reset();
    g.wanted_level = 0;
    snapshot::Player p = on_foot();
    for (int i = 1; i <= 10; ++i) {
        const char* err = cheats::run(cheats::kWantedUp, kPlayer, kPed, p);
        check(err == nullptr, "wanted up: ok");
        // Snapshot still reads the OLD wanted level this frame; the
        // next natives write bumps the game's level, and the fake
        // reflects it in g.wanted_level. On repeat, the snapshot stays
        // at 0 (we don't update it between taps), so run_one_shot
        // always writes current+1 = 1. The point of the cap check:
        // when the snapshot level reaches the cap, next writes still
        // land at cap, never past it.
        p.wanted_level = static_cast<uint8_t>(g.max_wanted >= 1 ? i : 0);
    }
    check(g.wanted_applied == 10, "apply-wanted: called every tap");
    check(g.wanted_level <= g.max_wanted, "wanted: never past the cap");

    const char* err = cheats::run(cheats::kWantedClear, kPlayer, kPed, p);
    check(err == nullptr, "wanted clear: ok");
    check(g.wanted_level == 0, "wanted clear: level = 0");
}

void test_all_weapons() {
    std::printf("all weapons\n");
    fake_reset();
    snapshot::Player p = on_foot();
    const char* err = cheats::run(cheats::kAllWeapons, kPlayer, kPed, p);
    check(err == nullptr, "all weapons: ok");
    check(g.given.size() == cheats::kAllWeaponsCount,
          "all weapons: one give per entry");
    bool saw_0 = false, saw_8 = false;
    for (const auto& [w, ammo] : g.given) {
        if (w == 0) saw_0 = true;
        if (w == 8) saw_8 = true;
        check(ammo == cheats::kFullAmmo, "all weapons: full ammo for each");
    }
    check(!saw_0, "all weapons: never weapon 0");
    check(!saw_8, "all weapons: never weapon 8 (empty slot)");
}

void test_max_ammo_skips_without_scan() {
    std::printf("max ammo without scan\n");
    fake_reset();
    snapshot::Player p = on_foot();   // owned_valid stays false
    const char* err = cheats::run(cheats::kMaxAmmo, kPlayer, kPed, p);
    check(err == nullptr, "max ammo: ok even without a scan");
    check(g.ammo_set.empty(), "max ammo: no writes when owned_valid is false");
}

void test_max_ammo_only_owned() {
    std::printf("max ammo with scan\n");
    fake_reset();
    snapshot::Player p = on_foot();
    p.owned_valid = true;
    p.owned_weapons[2] = 7;   // pistol
    p.owned_weapons[4] = 12;  // SMG
    p.owned_weapons[8] = 4;   // grenades
    const char* err = cheats::run(cheats::kMaxAmmo, kPlayer, kPed, p);
    check(err == nullptr, "max ammo: ok");
    check(g.ammo_set.size() == 3, "max ammo: one write per owned slot");
    bool saw_pistol = false, saw_smg = false, saw_grenade = false;
    for (const auto& [w, ammo] : g.ammo_set) {
        if (w == 7)  saw_pistol = true;
        if (w == 12) saw_smg = true;
        if (w == 4)  saw_grenade = true;
        check(ammo == cheats::kFullAmmo, "max ammo: full ammo per owned weapon");
    }
    check(saw_pistol && saw_smg && saw_grenade,
          "max ammo: every owned weapon written");
}

void test_fix_vehicle() {
    std::printf("fix vehicle\n");
    fake_reset();
    snapshot::Player foot = on_foot();
    const char* err = cheats::run(cheats::kFixVehicle, kPlayer, kPed, foot);
    check(err != nullptr && std::strcmp(err, cheats::kErrNotInVehicle) == 0,
          "fix vehicle on foot: refused with the right reason");
    check(g.fix_calls == 0 && g.wash_calls == 0,
          "fix vehicle on foot: no native fired");

    snapshot::Player car = in_vehicle();
    err = cheats::run(cheats::kFixVehicle, kPlayer, kPed, car);
    check(err == nullptr, "fix vehicle in car: ok");
    check(g.fix_calls == 1 && g.wash_calls == 1,
          "fix vehicle in car: both natives fired once");
}

void test_weather_cycle() {
    std::printf("weather cycle\n");
    fake_reset();
    snapshot::Player p = on_foot();
    for (uint32_t i = 0; i < cheats::kWeatherCount; ++i) {
        const char* err = cheats::run(cheats::kNextWeather, kPlayer, kPed, p);
        check(err == nullptr, "weather: ok");
        check(g.forced_weather == cheats::kWeatherIds[i],
              "weather: forced id matches the cycle entry");
    }
    check(g.forced_calls == static_cast<int>(cheats::kWeatherCount),
          "weather: one force per step");
    check(g.release_weather_calls == 0, "weather: no release yet");

    // Last step: release and wrap.
    cheats::run(cheats::kNextWeather, kPlayer, kPed, p);
    check(g.release_weather_calls == 1,
          "weather: last step released control to the game");
    check(g.forced_calls == static_cast<int>(cheats::kWeatherCount),
          "weather: release does not also force");

    // Wrap: next tap starts the cycle over.
    cheats::run(cheats::kNextWeather, kPlayer, kPed, p);
    check(g.forced_weather == cheats::kWeatherIds[0], "weather: wrap to 0");
}

void test_time_cycle() {
    std::printf("time of day cycle\n");
    fake_reset();
    snapshot::Player p = on_foot();

    g.clock_h = 7;   // next: 12
    cheats::run(cheats::kNextTimeOfDay, kPlayer, kPed, p);
    check(g.clock_h == 12 && g.clock_m == 0, "time: 07:30 -> 12:00");

    g.clock_h = 12;  // next: 18
    cheats::run(cheats::kNextTimeOfDay, kPlayer, kPed, p);
    check(g.clock_h == 18, "time: 12:00 -> 18:00");

    g.clock_h = 20;  // next: 00
    cheats::run(cheats::kNextTimeOfDay, kPlayer, kPed, p);
    check(g.clock_h == 0, "time: 20:00 -> 00:00");

    g.clock_h = 0;   // next: 06 (wrap)
    cheats::run(cheats::kNextTimeOfDay, kPlayer, kPed, p);
    check(g.clock_h == 6, "time: 00:00 -> 06:00");
}

void test_toggles_flip_mask_and_fire_natives() {
    std::printf("toggles\n");
    fake_reset();
    snapshot::Player p = on_foot();

    check(cheats::mask() == 0, "mask starts empty");

    cheats::run(cheats::kInvincible, kPlayer, kPed, p);
    check(g.invincible, "invincible on");
    check((cheats::mask() & (1u << (cheats::kInvincible - cheats::kFirstToggle))) != 0,
          "invincible: bit set");

    cheats::run(cheats::kSlowMotion, kPlayer, kPed, p);
    check(g.time_scale == 0.5f, "slow motion: time scale = 0.5");

    cheats::run(cheats::kFastReload, kPlayer, kPed, p);
    check(g.fast_reload, "fast reload on");

    cheats::run(cheats::kNeverTired, kPlayer, kPed, p);
    check(g.never_tired, "never tired on");

    cheats::run(cheats::kPoliceIgnore, kPlayer, kPed, p);
    check(g.police_ignore, "police ignore on");

    // Flip them all off.
    cheats::run(cheats::kInvincible, kPlayer, kPed, p);
    cheats::run(cheats::kSlowMotion, kPlayer, kPed, p);
    cheats::run(cheats::kFastReload, kPlayer, kPed, p);
    cheats::run(cheats::kNeverTired, kPlayer, kPed, p);
    cheats::run(cheats::kPoliceIgnore, kPlayer, kPed, p);
    check(!g.invincible && !g.fast_reload && !g.never_tired && !g.police_ignore,
          "all per-player toggles off");
    check(g.time_scale == 1.0f, "slow motion: back to 1.0");
    check(cheats::mask() == 0, "mask back to empty");
}

void test_reset_world_keeps_mask() {
    std::printf("reset_world\n");
    fake_reset();
    snapshot::Player p = on_foot();
    cheats::run(cheats::kSlowMotion, kPlayer, kPed, p);
    cheats::run(cheats::kInvincible, kPlayer, kPed, p);
    const uint32_t before = cheats::mask();

    cheats::reset_world();
    check(g.time_scale == 1.0f, "reset_world: time scale 1.0");
    check(g.release_weather_calls == 1, "reset_world: weather released");
    check(cheats::mask() == before, "reset_world: mask untouched");

    // tick() with a ped change re-applies, so the next frame re-sets
    // the time scale from the mask.
    cheats::tick(kPlayer, 0x3003);
    check(g.time_scale == 0.5f, "post-reset tick: slow motion re-applied");
    check(g.invincible, "post-reset tick: invincible re-applied");
}

void test_tick_reapplies_on_ped_change() {
    std::printf("ped change re-applies toggles\n");
    fake_reset();
    snapshot::Player p = on_foot();
    cheats::run(cheats::kInvincible, kPlayer, kPed, p);
    cheats::run(cheats::kFastReload, kPlayer, kPed, p);

    // Pretend the game cleared our writes behind our back (ped swap).
    g.invincible = false;
    g.fast_reload = false;

    // tick with the same ped: no-op.
    cheats::tick(kPlayer, kPed);
    check(!g.invincible, "tick: same ped, no re-apply");

    // Change the ped: every active toggle re-applies.
    cheats::tick(kPlayer, 0x4004);
    check(g.invincible && g.fast_reload,
          "tick: new ped, every active toggle re-applied");
}

void test_v_only_cheats_refused() {
    std::printf("V-only ids refused\n");
    fake_reset();
    snapshot::Player p = on_foot();
    const uint32_t v_only_ones[] = {
        cheats::kFillSpecial, cheats::kSpawnCar0, cheats::kSpawnCar5,
    };
    for (uint32_t id : v_only_ones) {
        const char* err = cheats::run(id, kPlayer, kPed, p);
        check(err != nullptr && std::strcmp(err, cheats::kErrNotInBuild) == 0,
              "V-only one-shot: refused with 'cheat not in this build'");
    }
    const uint32_t v_only_toggles[] = {
        cheats::kInfiniteClip, cheats::kFastRun, cheats::kLowGravity,
    };
    for (uint32_t id : v_only_toggles) {
        const char* err = cheats::run(id, kPlayer, kPed, p);
        check(err != nullptr && std::strcmp(err, cheats::kErrNotInBuild) == 0,
              "V-only toggle: refused with 'cheat not in this build'");
    }
    check(cheats::mask() == 0, "V-only taps: mask untouched");
}

void test_no_ped_refusal() {
    std::printf("no ped\n");
    fake_reset();
    snapshot::Player p{};  // in_game false, ped irrelevant
    const char* err = cheats::run(cheats::kHeal, kPlayer, 0, p);
    check(err != nullptr && std::strcmp(err, cheats::kErrNoPed) == 0,
          "ped 0: refused with 'no player ped'");
    err = cheats::run(cheats::kHeal, kPlayer, 0xFFFFFFFFu, p);
    check(err != nullptr && std::strcmp(err, cheats::kErrNoPed) == 0,
          "ped 0xFFFFFFFF: refused with 'no player ped'");
}

void test_unknown_id() {
    std::printf("unknown id\n");
    fake_reset();
    snapshot::Player p = on_foot();
    // 60 lives strictly between the one-shot bands and above
    // kMaxHighOneShot; must be refused as unknown.
    const char* err = cheats::run(60, kPlayer, kPed, p);
    check(err != nullptr && std::strcmp(err, cheats::kErrUnknown) == 0,
          "id 60 (above high one-shot range): unknown");
    err = cheats::run(0, kPlayer, kPed, p);
    check(err != nullptr && std::strcmp(err, cheats::kErrUnknown) == 0,
          "id 0: unknown");
    // Ids between the one-shot bands and the toggle range (35..39)
    // are also unknown.
    err = cheats::run(35, kPlayer, kPed, p);
    check(err != nullptr && std::strcmp(err, cheats::kErrUnknown) == 0,
          "id 35 (gap between toggle and high one-shot): unknown");
}

void test_weapon_sets() {
    std::printf("advanced + poor weapon sets\n");
    fake_reset();
    snapshot::Player p = on_foot();

    // Advanced: 8 weapons, the base-IV phone-cheat 486-555-0100 set.
    const char* err = cheats::run(cheats::kAdvancedWeapons, kPlayer, kPed, p);
    check(err == nullptr, "advanced weapons set: ok");
    check(g.given.size() == cheats::kAdvancedWeaponsCount,
          "advanced: one give per entry");
    bool saw_bat = false, saw_rpg = false;
    for (const auto& [w, ammo] : g.given) {
        if (w == 1)  saw_bat = true;
        if (w == 18) saw_rpg = true;
        check(ammo == cheats::kFullAmmo, "advanced: full ammo for each");
    }
    check(saw_bat && saw_rpg, "advanced: bat + RPG are in the set");

    fake_reset();
    p = on_foot();
    err = cheats::run(cheats::kPoorWeapons, kPlayer, kPed, p);
    check(err == nullptr, "poor weapons set: ok");
    check(g.given.size() == cheats::kPoorWeaponsCount,
          "poor: one give per entry");
    bool saw_knife = false, saw_molotov = false;
    for (const auto& [w, _ammo] : g.given) {
        if (w == 3) saw_knife = true;
        if (w == 5) saw_molotov = true;
    }
    check(saw_knife && saw_molotov,
          "poor: knife + molotov are in the set");
}

void test_parachute() {
    std::printf("parachute\n");
    fake_reset();
    snapshot::Player p = on_foot();
    const char* err = cheats::run(cheats::kGiveParachute, kPlayer, kPed, p);
    check(err == nullptr, "parachute: ok");
    check(g.given.size() == 1,
          "parachute: exactly one give");
    check(g.given.front().first == cheats::kWeaponParachute,
          "parachute: WEAPON_PARACHUTE (25)");
}

void test_ultimate() {
    std::printf("ultimate (482-555-0100)\n");
    fake_reset();
    // In a car: should repair; health / armor / ammo / advanced weapons all fire.
    snapshot::Player p = in_vehicle();
    const char* err = cheats::run(cheats::kUltimate, kPlayer, kPed, p);
    check(err == nullptr, "ultimate in car: ok");
    check(g.health == cheats::kMaxHealth, "ultimate: health = 200");
    check(g.armour == cheats::kMaxArmour, "ultimate: armour += 100");
    check(g.fix_calls == 1, "ultimate: fix fired once");
    check(g.wash_calls == 1, "ultimate: wash fired once");
    check(g.given.size() == cheats::kAdvancedWeaponsCount,
          "ultimate: advanced-weapons loadout given");

    // On foot: everything but repair.
    fake_reset();
    p = on_foot();
    err = cheats::run(cheats::kUltimate, kPlayer, kPed, p);
    check(err == nullptr, "ultimate on foot: ok");
    check(g.fix_calls == 0,
          "ultimate on foot: fix never fires");
    check(g.given.size() == cheats::kAdvancedWeaponsCount,
          "ultimate on foot: advanced-weapons loadout still given");
}

}  // namespace

int main() {
    test_heal();
    test_wanted();
    test_all_weapons();
    test_max_ammo_skips_without_scan();
    test_max_ammo_only_owned();
    test_fix_vehicle();
    test_weather_cycle();
    test_time_cycle();
    test_toggles_flip_mask_and_fire_natives();
    test_reset_world_keeps_mask();
    test_tick_reapplies_on_ped_change();
    test_v_only_cheats_refused();
    test_no_ped_refusal();
    test_unknown_id();
    test_weapon_sets();
    test_parachute();
    test_ultimate();
    std::printf("\n%d checks, %d failed\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}
