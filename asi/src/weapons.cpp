#include "weapons.h"

#include "log.h"
#include "weapon_natives.h"

namespace {

constexpr int kSlotCap = 13;   // snapshot::Player::owned_weapons

int32_t  g_weapons[kSlotCap] = {};
int32_t  g_ammo[kSlotCap] = {};
bool     g_scanned = false;
bool     g_rescan = false;     // LB went down: do not wait for the timer
uint32_t g_scan_ms = 0;
uint32_t g_ped = 0;

bool     g_lb_down = false;
uint32_t g_lb_since = 0;
bool     g_wheel_logged = false;

bool     g_dpad_down_prev = false;
bool     g_dpad_down_logged = false;

void scan(uint32_t ped) {
    for (int i = 0; i < kSlotCap; ++i) {
        g_weapons[i] = 0;
        g_ammo[i] = 0;
    }
    for (uint32_t slot = weapons::kFirstSlot; slot <= weapons::kLastSlot;
         ++slot) {
        uint32_t weapon = 0, ammo0 = 0, ammo1 = 0;
        game::get_char_weapon_in_slot(ped, slot, &weapon, &ammo0, &ammo1);
        // An empty slot reads as 0 (unarmed).
        if (weapon == weapons::kUnarmed || weapon >= weapons::kMaxWeaponId) {
            continue;
        }
        g_weapons[slot] = static_cast<int32_t>(weapon);
        // What ammo0 / ammo1 mean is not documented, so the count comes
        // from the native the HUD already uses for the weapon in hand.
        g_ammo[slot] =
            static_cast<int32_t>(game::get_ammo_in_char_weapon(ped, weapon));
    }
}

}  // namespace

namespace weapons {

static_assert(kLastSlot < kPedSlots, "scan would read past the ped's slots");
static_assert(kLastSlot < kSlotCap, "scan would not fit the snapshot");

void read_dpad_down(snapshot::Player& p) {
    const bool held = game::is_button_pressed(kPad, kButtonDpadDown);
    p.dpad_down_edge = held && !g_dpad_down_prev;
    if (p.dpad_down_edge && !g_dpad_down_logged) {
        g_dpad_down_logged = true;
        thor_log("[input] dpad-down observed on pad 0, button 9");
    }
    g_dpad_down_prev = held;
}

void read_wheel(uint32_t now_ms, snapshot::Player& p) {
    p.wheel = false;
    const bool down = p.in_game && !p.in_vehicle &&
                      game::is_button_pressed(kPad, kButtonLB);
    if (!down) {
        g_lb_down = false;
        g_wheel_logged = false;
        return;
    }
    if (!g_lb_down) {
        g_lb_down = true;
        g_lb_since = now_ms;
        // The companion is about to draw the loadout.
        g_rescan = true;
    }
    // Unsigned subtraction: correct across a GetTickCount wrap.
    p.wheel = now_ms - g_lb_since >= kWheelHoldMs;
    if (p.wheel && !g_wheel_logged) {
        // One line per hold: on a device this is the only way to tell
        // "LB is never seen" from "the companion ignores it".
        g_wheel_logged = true;
        thor_log("[weapons] LB held -> wheel on");
    }
}

void read_owned(uint32_t ped, uint32_t now_ms, snapshot::Player& p) {
    if (!g_scanned || g_rescan || ped != g_ped ||
        now_ms - g_scan_ms >= kScanMs) {
        const bool first = !g_scanned;
        scan(ped);
        g_scanned = true;
        g_rescan = false;
        g_scan_ms = now_ms;
        g_ped = ped;
        if (first) {
            int n = 0;
            for (int i = 0; i < kSlotCap; ++i) n += g_weapons[i] != 0;
            thor_log("[weapons] first owned-weapon scan ok: %d weapon(s) in "
                     "slots %u..%u", n, kFirstSlot, kLastSlot);
        }
    }
    for (int i = 0; i < kSlotCap; ++i) {
        p.owned_weapons[i] = g_weapons[i];
        p.owned_ammo[i] = g_ammo[i];
    }
    p.owned_valid = true;
}

const char* set_weapon_error(const snapshot::Player& p, uint32_t weapon) {
    if (p.in_vehicle) return "not on foot";
    if (weapon >= kMaxWeaponId) return "weapon id out of range";
    if (weapon == kUnarmed) return nullptr;
    // A build without the scan has nothing to check against; it keeps
    // the old behaviour of trusting the client.
    if (!p.owned_valid) return nullptr;
    for (int i = 0; i < kSlotCap; ++i) {
        if (p.owned_weapons[i] == static_cast<int32_t>(weapon)) return nullptr;
    }
    return "weapon not owned";
}

void reset() {
    for (int i = 0; i < kSlotCap; ++i) {
        g_weapons[i] = 0;
        g_ammo[i] = 0;
    }
    g_scanned = false;
    g_rescan = false;
    g_scan_ms = 0;
    g_ped = 0;
    g_lb_down = false;
    g_lb_since = 0;
    g_wheel_logged = false;
}

}  // namespace weapons
