#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdarg>
#include <cstdio>

#include "log.h"

namespace {
constexpr const char* kLogPath = "ThorBridge.log";
}

void thor_log(const char* fmt, ...) {
    FILE* f = std::fopen(kLogPath, "a");
    if (!f) {
        return;
    }

    SYSTEMTIME st;
    GetLocalTime(&st);
    std::fprintf(f, "[%02u:%02u:%02u.%03u] ",
                 st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);

    std::va_list ap;
    va_start(ap, fmt);
    std::vfprintf(f, fmt, ap);
    va_end(ap);

    std::fputc('\n', f);
    std::fclose(f);
}
