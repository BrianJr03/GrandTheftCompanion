#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "armory.h"
#include "blips.h"
#include "cheats.h"
#include "commands.h"
#include "log.h"
#include "mods.h"
#include "natives.h"
#include "shv.h"
#include "weapons.h"

namespace {

constexpr unsigned kQueueCap = 16;
constexpr unsigned kAckCap   = 32;
// A command that sat this long (game paused, loading) is refused rather
// than executed late: a radio retune that lands 30 s after the tap is
// worse than no retune.
constexpr uint64_t kStaleMs  = 2000;
constexpr uint32_t kRadioOff = 255;
// How long after the weapon-wheel control is released a deferred pick
// keeps being enforced. The game's wheel commits its own highlighted
// slot as it closes, possibly a few frames after the release; anything
// it selects inside this window is put back to the companion's pick.
constexpr uint64_t kPickSettleMs = 500;

CRITICAL_SECTION g_cs;
bool g_inited = false;

commands::Cmd g_queue[kQueueCap];
unsigned g_head = 0, g_tail = 0;

struct AckSlot {
    bool          used;
    uint32_t      client;
    uint64_t      seq;      // insertion order, for oldest-first eviction
    commands::Ack ack;
};
AckSlot  g_acks[kAckCap];
uint64_t g_ack_seq = 0;

struct Lock {
    Lock()  { EnterCriticalSection(&g_cs); }
    ~Lock() { LeaveCriticalSection(&g_cs); }
};

// A weapon picked on the companion while the weapon-wheel control was
// held. Script-fiber state only (set and consumed inside drain()), so
// it needs no lock.
struct WheelPick {
    bool     active;
    uint32_t hash;
    uint64_t released_ms;   // 0 while the control is still held
    bool     logged;        // "applied" already written to the log
};
WheelPick g_pick{};

void post_ack(uint32_t client, uint32_t id, bool ok, const char* err) {
    if (!g_inited) return;
    Lock l;
    AckSlot* slot = nullptr;
    for (auto& a : g_acks) {
        if (!a.used) { slot = &a; break; }
    }
    if (!slot) {
        // Mailbox full: acks for a client that went away pile up here.
        // Evict the oldest.
        slot = &g_acks[0];
        for (auto& a : g_acks) {
            if (a.seq < slot->seq) slot = &a;
        }
    }
    slot->used = true;
    slot->client = client;
    slot->seq = ++g_ack_seq;
    slot->ack.id = id;
    slot->ack.ok = ok;
    std::snprintf(slot->ack.err, sizeof(slot->ack.err), "%s", err ? err : "");
}

bool pop_cmd(commands::Cmd* out) {
    Lock l;
    if (g_head == g_tail) return false;
    *out = g_queue[g_tail];
    g_tail = (g_tail + 1) % kQueueCap;
    return true;
}

// Why commands are refused right now, or nullptr if they may run.
const char* blocked_reason(const snapshot::State& s) {
    if (s.online)    return "blocked: online";
    if (!s.in_game)  return "blocked: not in game";
    if (s.switching) return "blocked: switching character";
    if (s.cutscene)  return "blocked: cutscene";
    return nullptr;
}

void do_set_radio(const commands::Cmd& c, const snapshot::State& s) {
    if (!s.in_vehicle || !s.vehicle_has_radio) {
        post_ack(c.client, c.id, false, "not in a vehicle with a radio");
        return;
    }
    char name[snapshot::kNameLen];
    if (c.arg0 == kRadioOff) {
        std::snprintf(name, sizeof(name), "OFF");
    } else if (!snapshot::station_name(c.arg0, name, sizeof(name))) {
        post_ack(c.client, c.id, false, "radio station out of range");
        return;
    }
    // By NAME, never by index: the station order differs between game
    // builds, and the name is what the station list we published to
    // the client is keyed on.
    shv::call<void>(natives::SET_RADIO_TO_STATION_NAME,
                    static_cast<const char*>(name));
    thor_log("[cmd] set_radio %u (%s) -> ok", c.arg0, name);
    post_ack(c.client, c.id, true, "");
}

void do_set_weapon(const commands::Cmd& c, int32_t ped,
                   const snapshot::State& s) {
    if (s.in_vehicle) {
        // Drive-by weapon selection has its own rules; keep v1 simple.
        post_ack(c.client, c.id, false, "not on foot");
        return;
    }
    const uint32_t hash = c.arg0;
    if (hash != weapons::kUnarmed &&
        !shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, hash, 0)) {
        post_ack(c.client, c.id, false, "weapon not owned");
        return;
    }
    if (s.select_held) {
        // The game's weapon wheel is (about to be) open. Equipping now
        // would be undone when it closes and commits its own highlight,
        // so hold the pick until the control is released.
        g_pick = WheelPick{true, hash, 0, false};
        thor_log("[cmd] set_weapon 0x%08X -> ok (on wheel release)", hash);
        post_ack(c.client, c.id, true, "");
        return;
    }
    g_pick = WheelPick{};   // an immediate pick supersedes a pending one
    shv::call<void>(natives::SET_CURRENT_PED_WEAPON, ped, hash, 1);
    thor_log("[cmd] set_weapon 0x%08X -> ok", hash);
    post_ack(c.client, c.id, true, "");
}

// Runs once per tick, before new commands: equip a pick made while the
// weapon wheel was open, once the wheel has closed.
void settle_wheel_pick(int32_t ped, const snapshot::State& s, uint64_t now) {
    if (!g_pick.active) return;
    if (blocked_reason(s) || s.in_vehicle) {
        g_pick = WheelPick{};
        return;
    }
    if (s.select_held) {
        g_pick.released_ms = 0;   // still (or again) held: keep waiting
        return;
    }
    if (g_pick.released_ms == 0) g_pick.released_ms = now;
    if (now - g_pick.released_ms > kPickSettleMs) {
        g_pick = WheelPick{};
        return;
    }
    // s.weapon is this tick's GET_SELECTED_PED_WEAPON. Equal means the
    // pick is in place; stay armed for the rest of the window in case
    // the game's wheel commits after us.
    if (s.weapon == g_pick.hash) return;
    if (g_pick.hash != weapons::kUnarmed &&
        !shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, g_pick.hash, 0)) {
        g_pick = WheelPick{};
        return;
    }
    shv::call<void>(natives::SET_CURRENT_PED_WEAPON, ped, g_pick.hash, 1);
    if (!g_pick.logged) {
        g_pick.logged = true;
        thor_log("[cmd] wheel pick 0x%08X applied on release", g_pick.hash);
    } else {
        thor_log("[cmd] wheel pick 0x%08X re-applied (game changed it)",
                 g_pick.hash);
    }
}

}  // namespace

namespace commands {

void init() {
    if (g_inited) return;
    InitializeCriticalSection(&g_cs);
    g_inited = true;
}

void enqueue(const Cmd& c) {
    if (!g_inited) return;
    bool full = false;
    {
        Lock l;
        unsigned next = (g_head + 1) % kQueueCap;
        if (next == g_tail) {
            full = true;
        } else {
            g_queue[g_head] = c;
            g_head = next;
        }
    }
    if (full) post_ack(c.client, c.id, false, "server queue full");
}

bool take_ack(uint32_t client, Ack* out) {
    if (!g_inited) return false;
    Lock l;
    AckSlot* best = nullptr;
    for (auto& a : g_acks) {
        if (!a.used || a.client != client) continue;
        if (!best || a.seq < best->seq) best = &a;
    }
    if (!best) return false;
    *out = best->ack;
    best->used = false;
    return true;
}

// Public forwarder for the anonymous-namespace helper, so cheats::tick
// can post an ack when a deferred spawn-car command finally resolves.
void post_ack(uint32_t client, uint32_t id, bool ok, const char* err) {
    ::post_ack(client, id, ok, err);
}

void drain(int32_t player, int32_t ped, int32_t veh,
           const snapshot::State& s) {
    if (!g_inited) return;
    const uint64_t now = GetTickCount64();
    settle_wheel_pick(ped, s, now);
    Cmd c;
    while (pop_cmd(&c)) {
        if (now - c.queued_ms > kStaleMs) {
            post_ack(c.client, c.id, false, "stale: game was not running");
            continue;
        }
        if (const char* why = blocked_reason(s)) {
            post_ack(c.client, c.id, false, why);
            continue;
        }
        if (std::strcmp(c.name, "set_radio") == 0) {
            do_set_radio(c, s);
        } else if (std::strcmp(c.name, "set_weapon") == 0) {
            do_set_weapon(c, ped, s);
        } else if (std::strcmp(c.name, "route_blip") == 0) {
            char err[kMaxErrLen] = {0};
            const bool ok = blips::route_to(static_cast<int32_t>(c.arg0),
                                            err, sizeof(err));
            if (!ok) thor_log("[cmd] route_blip %u -> %s", c.arg0, err);
            post_ack(c.client, c.id, ok, err);
        } else if (std::strcmp(c.name, "route_clear") == 0) {
            blips::route_clear();
            post_ack(c.client, c.id, true, "");
        } else if (std::strcmp(c.name, "blip_census") == 0) {
            blips::log_census();
            post_ack(c.client, c.id, true, "");
        } else if (std::strcmp(c.name, "cheat") == 0) {
            // Spawn-car cheats defer their ack until the state machine
            // in cheats::tick resolves (model load may take a few
            // frames). Every other cheat posts its own ack from run().
            cheats::run(c.arg0, player, ped, s, c.client, c.id);
        } else if (std::strcmp(c.name, "paint_rgb") == 0) {
            // Paint-by-RGB. arg0 is the packed 0x00RRGGBB; route to
            // cheats::run with id=kCustomColour so run() reaches the
            // same code path as every other id-shaped paint cheat and
            // posts the ack from there. The command name is separate
            // because the one-u32 wire slot is already carrying the
            // colour — a `cheat` command would need the id there.
            cheats::run(cheats::kCustomColour, player, ped, s,
                        c.client, c.id, c.arg0);
        } else if (std::strcmp(c.name, "mods_list") == 0
                || std::strcmp(c.name, "set_mod") == 0
                || std::strcmp(c.name, "toggle_mod") == 0
                || std::strcmp(c.name, "set_wheel_type") == 0
                || std::strcmp(c.name, "set_tint") == 0
                || std::strcmp(c.name, "set_plate_style") == 0
                || std::strcmp(c.name, "set_plate") == 0
                || std::strcmp(c.name, "set_neon") == 0
                || std::strcmp(c.name, "set_smoke") == 0
                || std::strcmp(c.name, "set_xenon_colour") == 0
                || std::strcmp(c.name, "set_bulletproof_tyres") == 0) {
            char err[kMaxErrLen] = {0};
            const bool ok = mods::apply(c.name, c.arg0, c.text, veh,
                                        err, sizeof(err));
            post_ack(c.client, c.id, ok, err);
        } else if (std::strcmp(c.name, "armory_list") == 0
                || std::strcmp(c.name, "set_component") == 0
                || std::strcmp(c.name, "set_weapon_tint") == 0
                || std::strcmp(c.name, "set_ammo") == 0) {
            if (veh != 0) {
                post_ack(c.client, c.id, false, "not on foot");
                continue;
            }
            // armory_list carries the weapon hash in arg0; the set_*
            // commands pack their payload in arg0 and the server uses
            // the hash of the current page. armory::apply disambiguates
            // on name.
            const uint32_t req_hash =
                std::strcmp(c.name, "armory_list") == 0 ? c.arg0 : 0u;
            char err[kMaxErrLen] = {0};
            const bool ok = armory::apply(c.name, c.arg0, req_hash, ped,
                                          err, sizeof(err));
            post_ack(c.client, c.id, ok, err);
        } else {
            char err[kMaxErrLen];
            std::snprintf(err, sizeof(err), "unknown command: %.20s", c.name);
            post_ack(c.client, c.id, false, err);
        }
    }
}

void reject_all(const char* reason) {
    if (!g_inited) return;
    g_pick = WheelPick{};   // script fiber, same as drain()
    Cmd c;
    while (pop_cmd(&c)) post_ack(c.client, c.id, false, reason);
}

}  // namespace commands
