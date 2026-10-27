// ThorBridgeV.asi — AYN Thor second-screen companion plugin for GTA V
// (story mode). Sibling of asi/ (GTA IV); see README "GTA V".
//
// The two plugins share a wire protocol but nothing else:
//   * GTA IV: 32-bit, pattern-scans the exe, calls natives directly.
//   * GTA V:  64-bit, binds Script Hook V at runtime and lets it own
//     per-build compatibility. No pattern scans, no code patches, no
//     hooks of our own.
//
// If Script Hook V is missing or rejects the game build, the plugin
// stays in safe mode: the bridge still answers on 127.0.0.1:47801 with
// a hello frame saying what is wrong, and never touches the game.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "atlas.h"
#include "bridge.h"
#include "commands.h"
#include "log.h"
#include "script.h"
#include "shv.h"
#include "snapshot.h"
#include "version_info.h"

namespace {

constexpr DWORD kHeartbeatMs = 10 * 1000;

DWORD WINAPI heartbeat(LPVOID /*unused*/) {
    for (;;) {
        Sleep(kHeartbeatMs);
        thor_log("[heartbeat] alive (shv=%s script_started=%s)",
                 shv::available() ? "true" : "false",
                 script::started() ? "true" : "false");
    }
}

DWORD WINAPI startup(LPVOID /*unused*/) {
    VersionInfo vi = detect_version();
    thor_log("[init] host exe: %s file_version=%s size=%llu",
             vi.exe_name[0] ? vi.exe_name : "<unknown>",
             vi.file_version[0] ? vi.file_version : "<none>",
             static_cast<unsigned long long>(vi.size));
    if (!shv::available()) {
        thor_log("[init] SAFE MODE: %s", shv::error());
    }

    bridge::start(vi);
    CreateThread(NULL, 0, heartbeat, NULL, 0, NULL);
    return 0;
}

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
    case DLL_PROCESS_ATTACH:
        thor_log("=== ThorBridgeV %s loaded ===", bridge::kPluginVersion);
        // Critical sections first: the script fiber and socket workers
        // both use them and neither may win a race against init.
        snapshot::init();
        commands::init();
        atlas::init();

        // Script Hook V plugins register from DllMain; it queues the
        // script and starts it once the game's script engine is up.
        // The SDK does this through a static import, which also loads
        // ScriptHookV.dll under the loader lock — shv::load() is the
        // same thing done by hand.
        if (shv::load()) {
            shv::script_register(hinst, script::main);
            thor_log("[init] script registered with Script Hook V");
        }

        // Everything else (file IO, sockets) off the loader lock.
        CreateThread(NULL, 0, startup, NULL, 0, NULL);
        break;
    case DLL_PROCESS_DETACH:
        atlas::shutdown();
        shv::script_unregister(hinst);
        thor_log("=== ThorBridgeV unloaded ===");
        break;
    default:
        break;
    }
    return TRUE;
}
