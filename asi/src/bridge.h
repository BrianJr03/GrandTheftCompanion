#pragma once

// bridge:: — in-process TCP server that publishes newline-delimited JSON
// game-state frames over 127.0.0.1:47801. The companion app subscribes and
// renders. Owns a listener thread and one worker thread per connected
// client. Lifetime is DLL_PROCESS_ATTACH -> process exit; no clean shutdown.

namespace bridge {

// Start the server listener thread. Idempotent-ish — safe to call once from
// DllMain's DLL_PROCESS_ATTACH. Returns immediately; the server runs
// asynchronously. All reporting is via thor_log().
void start();

}  // namespace bridge
