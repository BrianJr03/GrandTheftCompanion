// ThorBridge.asi — AYN Thor second-screen companion plugin for GTA IV.
//
// Phase 2: boots the pattern-driven native-engine resolver and the
// CGameProcess hook. If any resolution step fails we stay in safe mode
// (hello frame only + tick counter). On success, bridge emits full
// `state` frames pulled from snapshot::.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "bridge.h"
#include "log.h"
#include "probes.h"
#include "snapshot.h"
#include "version_detect.h"

namespace {

constexpr DWORD kHeartbeatMs = 10 * 1000;

DWORD WINAPI heartbeat(LPVOID /*unused*/) {
    for (;;) {
        Sleep(kHeartbeatMs);
        thor_log("[heartbeat] alive (probes.enabled=%s)",
                 probes::enabled() ? "true" : "false");
    }
}

DWORD WINAPI startup(LPVOID /*unused*/) {
    thor_log("=== ThorBridge Phase 2 loaded ===");

    // Explicit snapshot init BEFORE spawning the server thread, so the
    // socket worker never races with the game-thread hook on first init
    // of the critical section.
    snapshot::init();

    VersionInfo vi = detect_version();
    thor_log("[init] exe fingerprint: crc32=0x%08X size=%u file_version=%s",
             vi.crc32, vi.size,
             vi.file_version[0] ? vi.file_version : "<none>");

    // Phase 2: probes orchestrates pattern-scanning + hook install on a
    // dedicated worker that polls until the exe is decrypted.
    probes::start_async();

    bridge::start(vi);
    CreateThread(NULL, 0, heartbeat, NULL, 0, NULL);
    return 0;
}

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE /*hinst*/, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
    case DLL_PROCESS_ATTACH:
        // Keep DllMain off the loader lock — hand off to a worker.
        CreateThread(NULL, 0, startup, NULL, 0, NULL);
        break;
    case DLL_PROCESS_DETACH:
        thor_log("=== ThorBridge unloaded ===");
        break;
    default:
        break;
    }
    return TRUE;
}
