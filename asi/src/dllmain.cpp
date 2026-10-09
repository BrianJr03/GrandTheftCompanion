// ThorBridge.asi — AYN Thor second-screen companion plugin for GTA IV.
//
// M3: starts the TCP bridge (127.0.0.1:47801) alongside a slow debug
// log heartbeat. The bridge thread accepts clients and streams newline-
// delimited JSON player-state frames at 20 Hz.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "bridge.h"
#include "log.h"
#include "probes.h"
#include "version_detect.h"

namespace {

// Low-frequency sanity log: lets us confirm from the log file that the ASI
// is alive and probing the game even when no client is connected to the
// bridge. 10 s cadence to avoid growing ThorBridge.log unboundedly during
// long sessions.
constexpr DWORD kHeartbeatMs = 10 * 1000;

DWORD WINAPI heartbeat(LPVOID /*unused*/) {
    for (;;) {
        Sleep(kHeartbeatMs);
        probes::PlayerState s = probes::sample();
        if (!s.valid) {
            thor_log("[heartbeat] no player (title/loading)");
        } else {
            thor_log("[heartbeat] health=%.1f max_health=%u max_armor=%u",
                     static_cast<double>(s.health),
                     static_cast<unsigned>(s.max_health),
                     static_cast<unsigned>(s.max_armor));
        }
    }
}

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE /*hinst*/, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
    case DLL_PROCESS_ATTACH: {
        thor_log("=== ThorBridge M3 loaded ===");

        const char* ver = probes::init();
        thor_log("[init] game version: %s", ver);

        VersionInfo vi = detect_version();
        thor_log("[init] exe fingerprint: crc32=0x%08X size=%u label=%s",
                 vi.crc32, vi.size, vi.label);

        bridge::start();
        CreateThread(NULL, 0, heartbeat, NULL, 0, NULL);
        break;
    }
    case DLL_PROCESS_DETACH:
        thor_log("=== ThorBridge unloaded ===");
        break;
    default:
        break;
    }
    return TRUE;
}
