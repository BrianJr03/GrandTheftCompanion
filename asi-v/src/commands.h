#pragma once

#include <cstddef>
#include <cstdint>

#include "snapshot.h"

// commands:: — client->server command queue, GTA V edition.
//
// Produced by per-client socket reader threads, consumed once per frame
// on the script fiber via drain(). Each command is answered with an Ack
// addressed to the client that sent it.
//
// Unlike asi/src/commands.h, acks are routed through one global mailbox
// keyed by a client id rather than through a pointer into the client's
// thread stack, so a client that disconnects while its command is still
// queued cannot leave a dangling pointer behind.

namespace commands {

constexpr size_t kMaxNameLen = 24;
constexpr size_t kMaxErrLen  = 64;

struct Ack {
    uint32_t id;
    bool     ok;
    char     err[kMaxErrLen];
};

struct Cmd {
    uint32_t client;              // bridge-assigned connection id
    uint32_t id;                  // client-assigned correlation id
    char     name[kMaxNameLen];
    uint32_t arg0;                // every current command takes one integer
    uint64_t queued_ms;           // GetTickCount64 at enqueue
};

// Call once before any other function (startup worker).
void init();

// Socket reader thread: queue a command. A full queue is answered with
// an immediate error ack.
void enqueue(const Cmd& c);

// Socket writer thread: fetch the next ack addressed to `client`.
bool take_ack(uint32_t client, Ack* out);

// Script fiber: run every queued command against the live game.
// `player`/`ped` are this frame's handles; `s` is this frame's state,
// used to refuse commands in states where they make no sense.
//
// Commands:
//   set_radio  arg0 = index into the published station list
//              (snapshot::Lists::stations), or 255 for "radio off".
//              Requires the player to be in a vehicle with a radio.
//   set_weapon arg0 = weapon hash. Requires the ped to own it.
void drain(int32_t player, int32_t ped, const snapshot::State& s);

// Script fiber, for frames where the game state is not usable (online,
// loading): answer everything queued with `reason`.
void reject_all(const char* reason);

}  // namespace commands
