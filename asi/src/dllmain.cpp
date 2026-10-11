// ThorBridge.asi — AYN Thor second-screen companion plugin for GTA IV.
//
// Phase 1 (safe mode by default): logs the exe fingerprint and starts a
// TCP server on 127.0.0.1:47801 that emits a `hello` frame + 20 Hz
// ticks. No memory reads, no hooks, no native invocations yet.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "bridge.h"
#include "log.h"
#include "probes.h"
#include "version_detect.h"

namespace {

// Low-frequency "ASI is alive" line to the log. Independent of whether a
// client is connected to the bridge. 10 s cadence to keep ThorBridge.log
// bounded during long sessions.
constexpr DWORD kHeartbeatMs = 10 * 1000;

DWORD WINAPI heartbeat(LPVOID /*unused*/) {
    for (;;) {
        Sleep(kHeartbeatMs);
        thor_log("[heartbeat] alive (probes.enabled=%s)",
                 probes::enabled() ? "true" : "false");
    }
}

DWORD WINAPI startup(LPVOID /*unused*/) {
    // All non-trivial work runs off DllMain to avoid the loader lock.
    thor_log("=== ThorBridge Phase 1 loaded ===");

    VersionInfo vi = detect_version();
    thor_log("[init] exe fingerprint: crc32=0x%08X size=%u file_version=%s",
             vi.crc32, vi.size,
             vi.file_version[0] ? vi.file_version : "<none>");

    const char* mode = probes::init();
    thor_log("[init] probes: %s", mode);

    bridge::start(vi);
    CreateThread(NULL, 0, heartbeat, NULL, 0, NULL);
    return 0;
}

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE /*hinst*/, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
    case DLL_PROCESS_ATTACH:
        // DllMain runs under the loader lock — do nothing heavy here. Hand
        // off to a worker thread that owns the real startup sequence.
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
