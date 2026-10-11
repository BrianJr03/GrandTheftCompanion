#pragma once

#include <cstdint>

// Reports a stable fingerprint of GTAIV.exe so the client knows what it is
// talking to. The CRC32 + size pair uniquely identifies a build; the
// file_version string ("1.2.0.59" etc.) is from the exe's version resource
// if present. `supported` is true only once Phase 2 lands pattern scans
// for the detected build.

struct VersionInfo {
    uint32_t crc32;
    uint32_t size;
    char file_version[32];   // from VS_FIXEDFILEINFO, or "" if absent
    bool supported;          // currently always false; Phase 2 flips per-build
};

VersionInfo detect_version();
