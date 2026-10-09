#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>

#include "log.h"

namespace {

// Three log destinations, union written to each line so we read whichever
// is accessible from the Android side. On Game Native (no root), the game's
// working dir is in app-private storage — unreachable via ADB. Winlator-
// family launchers mount Linux `/` under drive letter `Z:`, so a path like
// `Z:\sdcard\ThorBridge.log` resolves to `/sdcard/ThorBridge.log` on
// Android, which IS reachable via `adb pull`.
constexpr const char* kLogPathLocal = "ThorBridge.log";
constexpr const char* kLogPathSdcard = "Z:\\sdcard\\ThorBridge.log";

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

    // Local file next to GTAIV.exe. Always try — reachable via Game Native's
    // in-app file manager even without root.
    if (FILE* f = std::fopen(kLogPathLocal, "a")) {
        write_timestamped(f, body);
        std::fclose(f);
    }

    // Z:\sdcard path — ADB-readable if Winlator's Z: drive is mapped.
    // Opens silently fail if not mapped; no harm.
    if (FILE* f = std::fopen(kLogPathSdcard, "a")) {
        write_timestamped(f, body);
        std::fclose(f);
    }

    // Wine stderr → Game Native typically captures to
    // /sdcard/Android/data/app.gamenative/files/wine_logs/*.
    std::fprintf(stderr, "[ThorBridge] %s\n", body);
    std::fflush(stderr);
    OutputDebugStringA(body);
}
