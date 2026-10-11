#pragma once

#include <cstdint>

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
// Pass the current ped handle; commands requiring a ped use it.
// Posts an Ack per command to the originating client's queue.
void drain(uint32_t ped_handle);

}  // namespace commands
