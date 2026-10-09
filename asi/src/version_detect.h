#pragma once

#include <cstdint>

// Fingerprints GTAIV.exe so we can pick the correct offset profile for the
// user's build. The classic 1.0.8.0 / 1.0.7.0 CRC32s are stable and in the
// table below. The Anker Games repack the user plays will produce an
// as-yet-unknown CRC32 the first time we run against it — M8 adds it to
// the table.

struct VersionInfo {
    uint32_t crc32;
    uint32_t size;
    const char* label;  // human-readable identifier, "unknown" if unmatched
};

VersionInfo detect_version();
