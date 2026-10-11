#pragma once

#include <cstdint>

// Identity of the host executable, for the hello frame and the log.
// Unlike asi/src/version_detect.h there is no CRC: GTA5.exe is far
// larger than GTAIV.exe and nothing in this plugin depends on the exact
// build — Script Hook V owns per-build compatibility.
struct VersionInfo {
    uint64_t size;            // bytes, 0 if unknown
    char     exe_name[64];    // "GTA5.exe", "GTA5_Enhanced.exe", ...
    char     file_version[32];// "1.0.3889.0", or "" if absent
};

VersionInfo detect_version();
