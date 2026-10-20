// Phase 3 command queue. See commands.h for shape. Dispatches via
// natives:: on the game thread.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "blips.h"
#include "commands.h"
#include "log.h"
#include "natives.h"
#include "weapons.h"
#if THORBRIDGE_CHEATS
#include "cheat_natives.h"
#include "cheats.h"
#endif

namespace {

// Single global command queue — one shared queue is fine since the
// server only expects one client at a time. The reader thread pushes;
// drain() on the game thread pops.
CRITICAL_SECTION g_cmd_cs;
bool             g_cmd_cs_inited = false;

constexpr size_t kQueueCap = 32;
commands::Cmd    g_queue[kQueueCap];
unsigned         g_head = 0;
unsigned         g_tail = 0;

void ensure_cmd_cs() {
    if (!g_cmd_cs_inited) {
        InitializeCriticalSection(&g_cmd_cs);
        g_cmd_cs_inited = true;
    }
}

bool pop_cmd(commands::Cmd* out) {
    EnterCriticalSection(&g_cmd_cs);
    if (g_head == g_tail) {
        LeaveCriticalSection(&g_cmd_cs);
        return false;
    }
    *out = g_queue[g_tail];
    g_tail = (g_tail + 1) % kQueueCap;
    LeaveCriticalSection(&g_cmd_cs);
    return true;
}

void post_err_ack(uint32_t id, commands::AckQueue* q, const char* err) {
    if (!q) return;
    commands::Ack a{};
    a.id = id;
    a.ok = false;
    std::strncpy(a.err, err, commands::kMaxErrLen - 1);
    a.err[commands::kMaxErrLen - 1] = '\0';
    q->push(a);
}

void post_ok_ack(uint32_t id, commands::AckQueue* q) {
    if (!q) return;
    commands::Ack a{};
    a.id = id;
    a.ok = true;
    a.err[0] = '\0';
    q->push(a);
}

}  // namespace

namespace commands {

// -- AckQueue ----------------------------------------------------------

AckQueue::AckQueue() : cs_(nullptr), head_(0), tail_(0) {
    auto* cs = new CRITICAL_SECTION;
    InitializeCriticalSection(cs);
    cs_ = cs;
}

void AckQueue::push(const Ack& a) {
    auto* cs = static_cast<CRITICAL_SECTION*>(cs_);
    EnterCriticalSection(cs);
    unsigned next = (head_ + 1) % kCap;
    if (next != tail_) {
        buf_[head_] = a;
        head_ = next;
    }
    // On overflow we drop silently — reader is slow, client will
    // notice missing acks and resend.
    LeaveCriticalSection(cs);
}

bool AckQueue::pop(Ack* out) {
    auto* cs = static_cast<CRITICAL_SECTION*>(cs_);
    EnterCriticalSection(cs);
    if (head_ == tail_) {
        LeaveCriticalSection(cs);
        return false;
    }
    *out = buf_[tail_];
    tail_ = (tail_ + 1) % kCap;
    LeaveCriticalSection(cs);
    return true;
}

// -- Enqueue / drain ---------------------------------------------------

void enqueue(const Cmd& c) {
    ensure_cmd_cs();
    EnterCriticalSection(&g_cmd_cs);
    unsigned next = (g_head + 1) % kQueueCap;
    if (next == g_tail) {
        LeaveCriticalSection(&g_cmd_cs);
        post_err_ack(c.id, c.ack_queue, "server queue full");
        return;
    }
    g_queue[g_head] = c;
    g_head = next;
    LeaveCriticalSection(&g_cmd_cs);
}

void drain(uint32_t ped_handle, const snapshot::Player& p) {
    ensure_cmd_cs();
    Cmd c;
    while (pop_cmd(&c)) {
        if (std::strcmp(c.name, "set_weapon") == 0) {
            if (ped_handle == 0 || ped_handle == 0xFFFFFFFFu) {
                post_err_ack(c.id, c.ack_queue, "no player ped");
                continue;
            }
            // Id 0 (fists) is allowed: SET_CURRENT_CHAR_WEAPON with
            // WEAPON_UNARMED is how the game's own scripts holster. It
            // is the ammo natives that must never see weapon 0.
            if (const char* why = weapons::set_weapon_error(p, c.arg0)) {
                post_err_ack(c.id, c.ack_queue, why);
                continue;
            }
            natives::Context ctx;
            ctx.push(ped_handle);
            ctx.push(c.arg0);
            ctx.push(static_cast<uint32_t>(1));  // unknownTrue — forceEquip
            if (!natives::invoke(natives::SET_CURRENT_CHAR_WEAPON, ctx)) {
                post_err_ack(c.id, c.ack_queue, "set_weapon invoke failed");
                continue;
            }
            thor_log("[cmd] set_weapon id=%u -> ok", c.arg0);
            post_ok_ack(c.id, c.ack_queue);

        } else if (std::strcmp(c.name, "set_radio") == 0) {
            // 0..13 are the IV station indices; a few higher values are
            // DLC-only. Cap at 20 to be generous but reject garbage.
            // 255 is a reserved sentinel for "radio off": the native
            // accepts it and silences the current station without
            // tuning a new one.
            constexpr uint32_t kRadioOff = 255;
            if (c.arg0 != kRadioOff && c.arg0 >= 20) {
                post_err_ack(c.id, c.ack_queue, "radio station out of range");
                continue;
            }
            natives::Context ctx;
            ctx.push(c.arg0);
            if (!natives::invoke(natives::RETUNE_RADIO_TO_STATION_INDEX, ctx)) {
                post_err_ack(c.id, c.ack_queue, "set_radio invoke failed");
                continue;
            }
            thor_log("[cmd] set_radio station=%u -> ok", c.arg0);
            post_ok_ack(c.id, c.ack_queue);

        } else if (std::strcmp(c.name, "route_blip") == 0) {
#if THORBRIDGE_NATIVES_LEVEL >= 5
            if (const char* why = blips::route_to(static_cast<int32_t>(c.arg0))) {
                thor_log("[cmd] route_blip %u -> %s", c.arg0, why);
                post_err_ack(c.id, c.ack_queue, why);
                continue;
            }
            post_ok_ack(c.id, c.ack_queue);
#else
            post_err_ack(c.id, c.ack_queue, "route_blip: not in this build");
#endif

        } else if (std::strcmp(c.name, "route_clear") == 0) {
#if THORBRIDGE_NATIVES_LEVEL >= 5
            blips::route_clear();
            post_ok_ack(c.id, c.ack_queue);
#else
            post_err_ack(c.id, c.ack_queue, "route_clear: not in this build");
#endif

        } else if (std::strcmp(c.name, "blip_census") == 0) {
#if THORBRIDGE_NATIVES_LEVEL >= 5
            blips::log_census();
            post_ok_ack(c.id, c.ack_queue);
#else
            post_err_ack(c.id, c.ack_queue, "blip_census: not in this build");
#endif

        } else if (std::strcmp(c.name, "cheat") == 0) {
#if THORBRIDGE_CHEATS
            // Stale drop: a tap that waited through a pause can land
            // long after the player's attention has moved on. Only the
            // cheat command has this check — set_weapon / set_radio
            // keep their old behaviour.
            constexpr uint32_t kStaleMs = 2000;
            if (GetTickCount() - c.queued_ms > kStaleMs) {
                post_err_ack(c.id, c.ack_queue, "stale");
                continue;
            }
            const uint32_t player = game::get_player_id();
            if (player == 0xFFFFFFFFu) {
                post_err_ack(c.id, c.ack_queue, "no player");
                continue;
            }
            // Spawn-car ids kick off an async state machine that posts
            // its own ack from cheats::tick when the car is actually
            // under the player; every other cheat is synchronous.
            if (c.arg0 >= cheats::kSpawnIvFirst &&
                c.arg0 <= cheats::kSpawnIvLast) {
                const char* err = cheats::begin_spawn(
                    c.arg0, player, ped_handle, c.ack_queue, c.id,
                    GetTickCount());
                if (err) {
                    thor_log("[cheat] id=%u (spawn) -> %s", c.arg0, err);
                    post_err_ack(c.id, c.ack_queue, err);
                } else {
                    thor_log("[cheat] id=%u (spawn) armed", c.arg0);
                    // ack is deferred to cheats::tick
                }
                continue;
            }
            const char* err = cheats::run(c.arg0, player, ped_handle, p);
            if (err) {
                thor_log("[cheat] id=%u -> %s", c.arg0, err);
                post_err_ack(c.id, c.ack_queue, err);
            } else {
                thor_log("[cheat] id=%u -> ok (mask=0x%08X)",
                         c.arg0, cheats::mask());
                post_ok_ack(c.id, c.ack_queue);
            }
#else
            post_err_ack(c.id, c.ack_queue, "cheat not in this build");
#endif

        } else {
            char err[kMaxErrLen];
            std::snprintf(err, sizeof(err), "unknown cmd: %s", c.name);
            post_err_ack(c.id, c.ack_queue, err);
        }
    }
}

}  // namespace commands
