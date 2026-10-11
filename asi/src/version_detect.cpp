#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "version_detect.h"

namespace {

// CRC32-with-reflected-polynomial 0xEDB88320. Streamed so we never hold
// GTAIV.exe (~17 MB) in RAM.
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

void read_file_version_string(const char* exe_path, char out[32]) {
    out[0] = '\0';

    DWORD handle = 0;
    DWORD size = GetFileVersionInfoSizeA(exe_path, &handle);
    if (size == 0) return;

    void* data = HeapAlloc(GetProcessHeap(), 0, size);
    if (!data) return;

    if (GetFileVersionInfoA(exe_path, handle, size, data)) {
        VS_FIXEDFILEINFO* info = nullptr;
        UINT info_size = 0;
        if (VerQueryValueA(data, "\\",
                           reinterpret_cast<LPVOID*>(&info), &info_size)
            && info && info->dwSignature == 0xFEEF04BD) {
            std::snprintf(out, 32, "%u.%u.%u.%u",
                static_cast<unsigned>((info->dwFileVersionMS >> 16) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionMS      ) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionLS >> 16) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionLS      ) & 0xFFFFu));
        }
    }

    HeapFree(GetProcessHeap(), 0, data);
}

}  // namespace

VersionInfo detect_version() {
    VersionInfo out = {};
    out.file_version[0] = '\0';
    out.supported = false;

    char path[MAX_PATH];
    if (GetModuleFileNameA(NULL, path, MAX_PATH) == 0) {
        return out;
    }

    read_file_version_string(path, out.file_version);

    FILE* f = std::fopen(path, "rb");
    if (!f) return out;

    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.size = (sz > 0) ? static_cast<uint32_t>(sz) : 0;
    out.crc32 = crc32_stream(f);
    std::fclose(f);

    return out;
}
