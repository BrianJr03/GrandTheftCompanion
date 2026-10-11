#pragma once

#include "version_detect.h"

// bridge:: — in-process TCP server that publishes newline-delimited JSON
// frames over 127.0.0.1:47801. Phase 1 serves a one-shot `hello` frame
// (exe fingerprint + supported flag) followed by `tick` frames at 20 Hz.
// Phase 3 extends the server with a `state` frame and a client->server
// `cmd` command channel.

namespace bridge {

// Spawn the server listener thread. Idempotent-ish — safe to call once
// from DllMain's DLL_PROCESS_ATTACH. Returns immediately; the server runs
// asynchronously (WSAStartup happens on the worker thread, not DllMain).
// `vi` is captured by value for use in the hello frame.
void start(const VersionInfo& vi);

}  // namespace bridge
