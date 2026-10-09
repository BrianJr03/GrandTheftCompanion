// ThorBridge.asi — AYN Thor second-screen companion plugin for GTA IV.
//
// M2: detect the game version, fingerprint the exe, spawn a worker thread
// that samples player state once per second and logs it. No TCP yet
// (that's M3). All game-state access goes through probes::.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "log.h"
#include "probes.h"
#include "version_detect.h"

namespace {

DWORD WINAPI probe_loop(LPVOID /*unused*/) {
    thor_log("[probe] worker thread started");
    for (;;) {
        Sleep(1000);
        probes::PlayerState s = probes::sample();
        if (!s.valid) {
            thor_log("[probe] no player yet (title/loading)");
            continue;
        }
        thor_log("[probe] health=%.1f max_health=%u max_armor=%u",
                 s.health, s.max_health, s.max_armor);
    }
}

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE /*hinst*/, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
    case DLL_PROCESS_ATTACH: {
        thor_log("=== ThorBridge M2 loaded ===");

        const char* ver = probes::init();
        thor_log("[init] game version: %s", ver);

        VersionInfo vi = detect_version();
        thor_log("[init] exe fingerprint: crc32=0x%08X size=%u label=%s",
                 vi.crc32, vi.size, vi.label);

        CreateThread(NULL, 0, probe_loop, NULL, 0, NULL);
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
