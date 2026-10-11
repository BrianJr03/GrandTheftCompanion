// Phase 3 command queue. See commands.h for shape. Dispatches via
// natives:: on the game thread.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "commands.h"
#include "log.h"
#include "natives.h"

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

void drain(uint32_t ped_handle) {
    ensure_cmd_cs();
    Cmd c;
    while (pop_cmd(&c)) {
        if (std::strcmp(c.name, "set_weapon") == 0) {
            if (ped_handle == 0 || ped_handle == 0xFFFFFFFFu) {
                post_err_ack(c.id, c.ack_queue, "no player ped");
                continue;
            }
            if (c.arg0 == 0 || c.arg0 >= 200) {
                post_err_ack(c.id, c.ack_queue, "weapon id out of range");
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
            if (c.arg0 >= 20) {
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

        } else {
            char err[kMaxErrLen];
            std::snprintf(err, sizeof(err), "unknown cmd: %s", c.name);
            post_err_ack(c.id, c.ack_queue, err);
        }
    }
}

}  // namespace commands
