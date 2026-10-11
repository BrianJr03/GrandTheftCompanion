#pragma once

#include <cstdint>

// probes:: — the game-state reader. Owns the deferred-init worker that
// waits for GTA IV's code to be decrypted/mapped, resolves native-engine
// resolvers via patterns::, installs the CGameProcess hook via hook::,
// and once per frame reads player/vehicle/weapon state via natives::,
// publishing to snapshot::.
//
// Must be fired exactly once from DllMain's startup worker:
//     probes::start_async();
// Returns immediately. Progress is logged.

namespace probes {

void start_async();

// True once the deferred-init worker has (a) matched the init-gate
// pattern, (b) resolved natives::, and (c) installed the CGameProcess
// hook. The ASI bridge consults this via natives::ok_to_invoke() and
// snapshot::has_ever_been_in_game() to decide what frame type to emit.
bool enabled();

}  // namespace probes
