#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>

#include "version_detect.h"

namespace {

struct Known {
    uint32_t crc32;
    uint32_t size;
    const char* label;
};

// Fill this in as we fingerprint builds. M8 pins the user's actual Anker
// Games build. The sentinel {0,0} is unreachable (any real exe has nonzero
// size) and exists only so the array isn't zero-sized under -Wpedantic.
constexpr Known kKnown[] = {
    {0, 0, "sentinel"},
    // {0x????????, ????????, "classic 1.0.7.0"},
    // {0x????????, ????????, "classic 1.0.8.0"},
    // {0x????????, ????????, "complete-edition 1.2.0.59"},
    // {0x????????, ????????, "anker-games repack"},
};

uint32_t crc32_stream(FILE* f) {
    static uint32_t table[256];
    static bool built = false;
    if (!built) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int j = 0; j < 8; ++j) {
                c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            }
            table[i] = c;
        }
        built = true;
    }

    uint32_t crc = 0xFFFFFFFFu;
    uint8_t buf[64 * 1024];
    for (;;) {
        size_t n = std::fread(buf, 1, sizeof(buf), f);
        if (n == 0) break;
        for (size_t i = 0; i < n; ++i) {
            crc = table[(crc ^ buf[i]) & 0xFFu] ^ (crc >> 8);
        }
    }
    return crc ^ 0xFFFFFFFFu;
}

}  // namespace

VersionInfo detect_version() {
    VersionInfo out = {0, 0, "no-exe"};

    char path[MAX_PATH];
    if (GetModuleFileNameA(NULL, path, MAX_PATH) == 0) {
        return out;
    }

    FILE* f = std::fopen(path, "rb");
    if (!f) {
        return out;
    }

    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);

    out.size = (sz > 0) ? static_cast<uint32_t>(sz) : 0;
    out.crc32 = crc32_stream(f);
    std::fclose(f);

    out.label = "unknown";
    for (const auto& k : kKnown) {
        if (k.crc32 == out.crc32 && k.size == out.size) {
            out.label = k.label;
            break;
        }
    }
    return out;
}
