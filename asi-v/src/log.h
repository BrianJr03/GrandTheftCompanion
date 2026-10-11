#pragma once

// printf-style logger. Writes to ThorBridgeV.log next to GTA5.exe, to
// stderr (captured by the launcher's Wine log), and to the debugger.
// Safe to call from any thread.
void thor_log(const char* fmt, ...)
#if defined(__GNUC__)
    __attribute__((format(printf, 1, 2)))
#endif
    ;
