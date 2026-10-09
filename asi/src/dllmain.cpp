// ThorBridge.asi — AYN Thor second-screen companion plugin for GTA IV.
//
// Loaded by Ultimate ASI Loader into GTA IV's process at startup. Will later
// read live game state via IV-SDK natives and publish it over localhost TCP
// to the Thor's companion app. For M1, this is a hello-world: we just want
// DLL_PROCESS_ATTACH to fire inside the game and a log line to land.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>

namespace {

// GTA IV's working directory when launched is the install dir, so this
// lands next to GTAIV.exe. Game Native's Wine prefix maps that to a real
// file on Android storage, so we can retrieve it to confirm the ASI loaded.
constexpr const char* kLogPath = "ThorBridge.log";

void write_log(const char* msg) {
    FILE* f = std::fopen(kLogPath, "a");
    if (!f) {
        return;
    }
    std::fprintf(f, "%s\n", msg);
    std::fclose(f);
}

}  // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE /*hinst*/, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
    case DLL_PROCESS_ATTACH:
        write_log("[ThorBridge] loaded — M1 hello world");
        break;
    case DLL_PROCESS_DETACH:
        write_log("[ThorBridge] unloaded");
        break;
    default:
        break;
    }
    return TRUE;
}
