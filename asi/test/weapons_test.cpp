// Host-side test for src/weapons.cpp, the logic behind the companion's
// GTA IV weapon wheel. Built with the machine's own C++ compiler and
// run directly: no mingw, no Wine, no game.
//
// The three natives weapons.cpp calls (weapon_natives.h) are replaced
// here by a fake ped. The fake enforces the contract the real natives
// are believed to have, and fails the test when it is broken:
//   * GET_CHAR_WEAPON_IN_SLOT: slot must be inside the ped's 12-entry
//     weapon array, and all three out-pointers must be valid. Reading
//     slot 12 is what crashed the game the first time this was tried.
//   * GET_AMMO_IN_CHAR_WEAPON: never weapon 0, never a garbage id.
//   * IS_BUTTON_PRESSED: only pad 0, button 4 (LB).
//
// What this proves: the scan stays inside the array, the cache and
// rescan rules, the LB hold filter, and the set_weapon checks. What it
// cannot prove: that the real natives behave like the fake, or that the
// marshalling in weapon_natives.cpp is right. That needs the game.

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "../src/weapon_natives.h"
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

constexpr uint32_t kPed = 0x1001;
uint32_t g_ped = kPed;                  // the handle the natives expect

uint32_t g_slots[weapons::kPedSlots];   // weapon id per slot
uint32_t g_ammo_of[weapons::kMaxWeaponId];
bool     g_lb = false;

int      g_slot_calls = 0;
int      g_ammo_calls = 0;
int      g_button_calls = 0;
int      g_logs = 0;
unsigned g_slots_seen = 0;              // bit per slot index read

void fake_reset() {
    std::memset(g_slots, 0, sizeof(g_slots));
    std::memset(g_ammo_of, 0, sizeof(g_ammo_of));
    g_lb = false;
    g_ped = kPed;
    g_slot_calls = g_ammo_calls = g_button_calls = g_logs = 0;
    g_slots_seen = 0;
    weapons::reset();
}

// Niko mid-game: bat, pistol, pump shotgun, micro-SMG, AK, grenades.
void give_loadout() {
    g_slots[1] = 1;  g_ammo_of[1] = 1;      // baseball bat
    g_slots[2] = 7;  g_ammo_of[7] = 120;    // pistol
    g_slots[3] = 10; g_ammo_of[10] = 24;    // pump shotgun
    g_slots[4] = 12; g_ammo_of[12] = 300;   // micro-SMG
    g_slots[5] = 14; g_ammo_of[14] = 240;   // AK-47
    g_slots[8] = 4;  g_ammo_of[4] = 5;      // grenades
}

snapshot::Player on_foot() {
    snapshot::Player p{};
    p.in_game = true;
    return p;
}

}  // namespace

namespace game {

void get_char_weapon_in_slot(uint32_t ped, uint32_t slot, uint32_t* weapon,
                             uint32_t* ammo0, uint32_t* ammo1) {
    ++g_slot_calls;
    check(ped == g_ped, "slot native: called with the player ped");
    check(slot < weapons::kPedSlots,
          "slot native: slot inside the ped's weapon array");
    check(weapon && ammo0 && ammo1,
          "slot native: all three out-pointers supplied");
    if (slot >= weapons::kPedSlots || !weapon || !ammo0 || !ammo1) return;
    g_slots_seen |= 1u << slot;
    *weapon = g_slots[slot];
    *ammo0 = 0xAAAA;   // deliberately not the real count
    *ammo1 = 0xBBBB;
}

uint32_t get_ammo_in_char_weapon(uint32_t ped, uint32_t weapon) {
    ++g_ammo_calls;
    check(ped == g_ped, "ammo native: called with the player ped");
    check(weapon != 0, "ammo native: never asked about weapon 0");
    check(weapon < weapons::kMaxWeaponId, "ammo native: sane weapon id");
    return weapon < weapons::kMaxWeaponId ? g_ammo_of[weapon] : 0;
}

bool is_button_pressed(uint32_t pad, uint32_t button) {
    ++g_button_calls;
    check(pad == 0 && button == 4, "button native: pad 0, LB");
    return g_lb;
}

}  // namespace game

void thor_log(const char* /*fmt*/, ...) { ++g_logs; }

// ---- tests -----------------------------------------------------------

namespace {

void test_scan_bounds_and_contents() {
    std::printf("owned-weapon scan\n");
    fake_reset();
    give_loadout();
    // Things the wheel must not pick up, in slots the scan must not read.
    g_slots[0] = 0;
    g_slots[10] = 30;   // gift slot
    g_slots[11] = 41;   // parachute slot

    snapshot::Player p = on_foot();
    weapons::read_owned(kPed, 5000, p);

    check(g_slot_calls == 9, "one slot read per scanned slot (9)");
    check(g_slots_seen == 0x3FEu, "exactly slots 1..9 were read");
    check(p.owned_valid, "owned_valid set");
    check(p.owned_weapons[1] == 1 && p.owned_weapons[2] == 7 &&
          p.owned_weapons[3] == 10 && p.owned_weapons[4] == 12 &&
          p.owned_weapons[5] == 14 && p.owned_weapons[8] == 4,
          "weapon ids land in their game slots");
    check(p.owned_weapons[6] == 0 && p.owned_weapons[7] == 0 &&
          p.owned_weapons[9] == 0, "empty slots stay 0");
    check(p.owned_weapons[0] == 0 && p.owned_weapons[10] == 0 &&
          p.owned_weapons[11] == 0 && p.owned_weapons[12] == 0,
          "unscanned slots stay 0");
    check(p.owned_ammo[2] == 120 && p.owned_ammo[5] == 240 &&
          p.owned_ammo[8] == 5,
          "ammo comes from the ammo native, not ammo0/ammo1");
    check(g_ammo_calls == 6, "ammo asked once per carried weapon");
    check(g_logs == 1, "first scan is logged once");
}

void test_garbage_ids() {
    std::printf("garbage weapon ids\n");
    fake_reset();
    g_slots[2] = 7;  g_ammo_of[7] = 50;
    g_slots[4] = 0xDEADBEEF;            // transient / garbage read
    g_slots[5] = weapons::kMaxWeaponId; // first invalid id

    snapshot::Player p = on_foot();
    weapons::read_owned(kPed, 0, p);
    check(p.owned_weapons[2] == 7, "valid weapon kept");
    check(p.owned_weapons[4] == 0 && p.owned_weapons[5] == 0,
          "out-of-range ids dropped");
    check(g_ammo_calls == 1, "ammo native not called for dropped ids");
}

void test_cache_and_rescan() {
    std::printf("cache and rescan\n");
    fake_reset();
    give_loadout();
    snapshot::Player p = on_foot();
    weapons::read_owned(kPed, 10000, p);
    const int after_first = g_slot_calls;

    // Frames inside the interval reuse the scan, but still fill p.
    g_slots[6] = 16;  g_ammo_of[16] = 10;   // picked up a sniper rifle
    snapshot::Player q = on_foot();
    weapons::read_owned(kPed, 10000 + weapons::kScanMs - 1, q);
    check(g_slot_calls == after_first, "no native calls inside the interval");
    check(q.owned_valid && q.owned_weapons[2] == 7 && q.owned_weapons[6] == 0,
          "cached result served");

    snapshot::Player r = on_foot();
    weapons::read_owned(kPed, 10000 + weapons::kScanMs, r);
    check(g_slot_calls == after_first + 9, "rescan when the interval is up");
    check(r.owned_weapons[6] == 16 && r.owned_ammo[6] == 10,
          "rescan sees the new weapon");

    // A different ped (save loaded) must not inherit the old loadout.
    std::memset(g_slots, 0, sizeof(g_slots));
    g_ped = 0x2002;
    snapshot::Player s = on_foot();
    weapons::read_owned(g_ped, 10000 + weapons::kScanMs + 1, s);
    check(g_slot_calls == after_first + 18, "ped change forces a rescan");
    check(s.owned_weapons[2] == 0, "old ped's weapons are gone");
}

void test_clock_wrap() {
    std::printf("tick-count wrap\n");
    fake_reset();
    give_loadout();
    snapshot::Player p = on_foot();
    weapons::read_owned(kPed, 0xFFFFFF00u, p);
    const int n = g_slot_calls;
    snapshot::Player q = on_foot();
    weapons::read_owned(kPed, 0x00000010u, q);     // 0x110 ms later
    check(g_slot_calls == n, "wrap inside the interval: no rescan");
    snapshot::Player r = on_foot();
    weapons::read_owned(kPed, 0xFFFFFF00u + weapons::kScanMs, r);
    check(g_slot_calls == n + 9, "wrap past the interval: rescan");
}

void test_wheel_hold_filter() {
    std::printf("LB hold filter\n");
    fake_reset();
    snapshot::Player p = on_foot();
    weapons::read_wheel(1000, p);
    check(!p.wheel && g_button_calls == 1, "LB up: no wheel");

    g_lb = true;
    p = on_foot(); weapons::read_wheel(2000, p);
    check(!p.wheel, "LB just pressed: not yet");
    p = on_foot(); weapons::read_wheel(2000 + weapons::kWheelHoldMs - 1, p);
    check(!p.wheel, "just short of the hold time: not yet");
    p = on_foot(); weapons::read_wheel(2000 + weapons::kWheelHoldMs, p);
    check(p.wheel, "held long enough: wheel on");
    p = on_foot(); weapons::read_wheel(9000, p);
    check(p.wheel, "still held: still on");
    check(g_logs == 1, "one log line per hold");

    g_lb = false;
    p = on_foot(); p.wheel = true; weapons::read_wheel(9016, p);
    check(!p.wheel, "released: wheel off");

    // A tap (the game's pick-up press) never shows the wheel.
    g_lb = true;
    p = on_foot(); weapons::read_wheel(20000, p);
    p = on_foot(); weapons::read_wheel(20080, p);
    check(!p.wheel, "80 ms tap: no wheel");
    g_lb = false;
    p = on_foot(); weapons::read_wheel(20100, p);
    g_lb = true;
    p = on_foot(); weapons::read_wheel(20200, p);
    check(!p.wheel, "a second tap does not inherit the first one's time");
    check(g_logs == 1, "taps are not logged");
}

void test_wheel_only_on_foot() {
    std::printf("LB off foot\n");
    fake_reset();
    g_lb = true;
    snapshot::Player p = on_foot();
    p.in_vehicle = true;
    weapons::read_wheel(0, p);
    weapons::read_wheel(5000, p);
    check(!p.wheel, "in a vehicle: no wheel");
    check(g_button_calls == 0, "in a vehicle: LB is not even polled");

    snapshot::Player q{};   // in_game false
    weapons::read_wheel(6000, q);
    check(!q.wheel && g_button_calls == 0, "not in game: no wheel, no poll");

    // Stepping out with LB already down starts the clock then, not
    // from when it was first pressed in the car.
    snapshot::Player r = on_foot();
    weapons::read_wheel(7000, r);
    check(!r.wheel, "hold time starts when LB is first seen on foot");
}

void test_press_forces_rescan() {
    std::printf("LB press rescans\n");
    fake_reset();
    give_loadout();
    snapshot::Player p = on_foot();
    weapons::read_wheel(1000, p);
    weapons::read_owned(kPed, 1000, p);
    const int n = g_slot_calls;

    g_slots[7] = 18;  g_ammo_of[18] = 3;    // RPG picked up since
    g_lb = true;
    p = on_foot();
    weapons::read_wheel(1100, p);            // well inside kScanMs
    weapons::read_owned(kPed, 1100, p);
    check(g_slot_calls == n + 9, "press rescans in the same frame");
    check(p.owned_weapons[7] == 18, "wheel opens on a fresh loadout");

    p = on_foot();
    weapons::read_wheel(1116, p);
    weapons::read_owned(kPed, 1116, p);
    check(g_slot_calls == n + 9, "holding does not rescan every frame");
}

void test_set_weapon_rules() {
    std::printf("set_weapon checks\n");
    fake_reset();
    give_loadout();
    snapshot::Player p = on_foot();
    weapons::read_owned(kPed, 0, p);

    check(weapons::set_weapon_error(p, 7) == nullptr, "carried weapon: ok");
    check(weapons::set_weapon_error(p, weapons::kUnarmed) == nullptr,
          "fists: always ok");
    const char* e = weapons::set_weapon_error(p, 18);
    check(e && !std::strcmp(e, "weapon not owned"), "not carried: refused");
    e = weapons::set_weapon_error(p, weapons::kMaxWeaponId);
    check(e && !std::strcmp(e, "weapon id out of range"), "garbage id: refused");
    e = weapons::set_weapon_error(p, 0xFFFFFFFFu);
    check(e && !std::strcmp(e, "weapon id out of range"),
          "negative id from a client: refused");

    snapshot::Player car = p;
    car.in_vehicle = true;
    e = weapons::set_weapon_error(car, 7);
    check(e && !std::strcmp(e, "not on foot"), "in a vehicle: refused");

    // Build without the scan (natives level < 4): nothing to check
    // ownership against, so ids in range are trusted as before.
    snapshot::Player old = on_foot();
    check(!old.owned_valid && weapons::set_weapon_error(old, 18) == nullptr,
          "no scan in this build: ownership not enforced");
    e = weapons::set_weapon_error(old, 500);
    check(e != nullptr, "no scan in this build: range still enforced");
}

}  // namespace

int main() {
    test_scan_bounds_and_contents();
    test_garbage_ids();
    test_cache_and_rescan();
    test_clock_wrap();
    test_wheel_hold_filter();
    test_wheel_only_on_foot();
    test_press_forces_rescan();
    test_set_weapon_rules();
    std::printf("\n%d checks, %d failed\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}
