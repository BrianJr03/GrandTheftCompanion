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
#include "natives.h"
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

// Diagnostic thread for round 3: samples the resolved pause-gate
// booleans every 2 s for the first 2 minutes, so the log captures
// their actual transitions during a real save-load + gameplay session.
// If the "user paused" or "script paused" byte never changes from 0,
// the pointer is almost certainly wrong. If it toggles during pauses /
// cutscenes / loads, the pointer is right.
DWORD WINAPI pause_sampler(LPVOID /*unused*/) {
    bool prev_u = false, prev_s = false;
    bool have_prev = false;
    for (int i = 0; i < 60; ++i) {
        Sleep(2000);
        bool u = false, s = false;
        if (!natives::debug_read_pause(&u, &s)) continue;
        if (!have_prev || u != prev_u || s != prev_s) {
            thor_log("[diag] pause: user=%d script=%d (t=%d s%s)",
                     u ? 1 : 0, s ? 1 : 0, i * 2,
                     have_prev ? " TRANSITION" : "");
            prev_u = u; prev_s = s; have_prev = true;
        }
    }
    return 0;
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
    CreateThread(NULL, 0, pause_sampler, NULL, 0, NULL);
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
