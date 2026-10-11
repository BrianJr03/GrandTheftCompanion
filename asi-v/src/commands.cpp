#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "commands.h"
#include "log.h"
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
    shv::call<void>(natives::SET_CURRENT_PED_WEAPON, ped, hash, 1);
    thor_log("[cmd] set_weapon 0x%08X -> ok", hash);
    post_ack(c.client, c.id, true, "");
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

void drain(int32_t /*player*/, int32_t ped, const snapshot::State& s) {
    if (!g_inited) return;
    const uint64_t now = GetTickCount64();
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
        } else {
            char err[kMaxErrLen];
            std::snprintf(err, sizeof(err), "unknown command: %.20s", c.name);
            post_ack(c.client, c.id, false, err);
        }
    }
}

void reject_all(const char* reason) {
    if (!g_inited) return;
    Cmd c;
    while (pop_cmd(&c)) post_ack(c.client, c.id, false, reason);
}

}  // namespace commands
