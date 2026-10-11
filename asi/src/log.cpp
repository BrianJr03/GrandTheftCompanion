#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>

#include "log.h"

namespace {

// Lazily-resolved absolute log path next to GTAIV.exe. Resolving once at
// first use avoids depending on the game's CWD (Wine may chdir after
// start-up) and tolerates DllMain being called very early. We fall back to
// a plain `ThorBridge.log` relative path on failure.
char g_log_path[MAX_PATH] = {0};
bool g_log_path_resolved = false;

const char* log_path() {
    if (!g_log_path_resolved) {
        char exe[MAX_PATH];
        DWORD n = GetModuleFileNameA(NULL, exe, MAX_PATH);
        if (n == 0 || n == MAX_PATH) {
            std::strcpy(g_log_path, "ThorBridge.log");
        } else {
            // Trim to the directory separator before the exe filename, then
            // append our log filename.
            char* sep = std::strrchr(exe, '\\');
            if (sep) {
                *(sep + 1) = '\0';
                std::snprintf(g_log_path, sizeof(g_log_path),
                              "%sThorBridge.log", exe);
            } else {
                std::strcpy(g_log_path, "ThorBridge.log");
            }
        }
        g_log_path_resolved = true;
    }
    return g_log_path;
}

void write_timestamped(FILE* f, const char* line_body) {
    SYSTEMTIME st;
    GetLocalTime(&st);
    std::fprintf(f, "[%02u:%02u:%02u.%03u] %s\n",
                 st.wHour, st.wMinute, st.wSecond, st.wMilliseconds,
                 line_body);
}

}  // namespace

void thor_log(const char* fmt, ...) {
    char body[1024];
    {
        std::va_list ap;
        va_start(ap, fmt);
        int n = std::vsnprintf(body, sizeof(body), fmt, ap);
        va_end(ap);
        if (n < 0) {
            std::strcpy(body, "<log formatting error>");
        }
    }

    // Primary channel: file in the game folder. On GameHub Lite this folder
    // is on shared storage (/sdcard), so `adb pull` reaches it without root.
    if (FILE* f = std::fopen(log_path(), "a")) {
        write_timestamped(f, body);
        std::fclose(f);
    }

    // Extras: Wine stderr (captured by GameHub Lite under
    // /sdcard/Android/data/gamehub.lite/files/log/*_wine.txt), and the
    // Windows debugger stream.
    std::fprintf(stderr, "[ThorBridge] %s\n", body);
    std::fflush(stderr);
    OutputDebugStringA(body);
}
