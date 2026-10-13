#pragma once

#include <cstdint>

#include "snapshot.h"

// commands:: — thread-safe producer/consumer queue for client->server
// commands. Produced by the per-client socket reader threads; consumed
// once per frame on the game thread via drain(ped_handle). drain()
// dispatches each command via natives:: and posts an Ack back to the
// originating client's ack queue so the writer can send it.

namespace commands {

constexpr size_t kMaxNameLen = 24;
constexpr size_t kMaxErrLen  = 64;

struct Ack {
    uint32_t id;
    bool     ok;
    char     err[kMaxErrLen];
};

// Fixed-size SPSC-style ring sized for one client. One of these lives
// in each ClientCtx. The writer thread pops acks and sends them.
class AckQueue {
public:
    AckQueue();
    void push(const Ack& a);         // reader-thread/game-thread safe
    bool pop(Ack* out);              // writer-thread safe

private:
    static constexpr size_t kCap = 16;
    void*    cs_;                    // CRITICAL_SECTION* (opaque)
    Ack      buf_[kCap];
    unsigned head_;
    unsigned tail_;
};

struct Cmd {
    uint32_t   id;
    char       name[kMaxNameLen];
    uint32_t   arg0;                 // most commands take a single int
    AckQueue*  ack_queue;            // where to post the ack
};

// Enqueue a command. Called from the socket reader thread. If the queue
// is full the command is dropped and an immediate err ack is posted.
void enqueue(const Cmd& c);

// Drain and dispatch pending commands. Must run on the game thread
// AFTER the per-frame snapshot read (so we have a fresh ped handle).
// Pass the current ped handle and this frame's snapshot; commands use
// the snapshot to refuse what makes no sense right now.
// Posts an Ack per command to the originating client's queue.
//
// Commands:
//   set_weapon arg0 = weapon id, 0 for fists. On foot only. When the
//              build scans owned weapons (p.owned_valid) the player
//              must be carrying it.
//   set_radio  arg0 = station index.
//   route_blip arg0 = blip id from the published blip list. Turns the
//              game's GPS route on for that blip (SET_ROUTE), off for
//              the one routed before. Natives level 5; refused for an
//              id the last census did not list.
//   route_clear no arg. Turns the route off.
//   blip_census no arg. Writes the last census to the log.
void drain(uint32_t ped_handle, const snapshot::Player& p);

}  // namespace commands
