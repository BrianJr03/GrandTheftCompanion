#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "version_info.h"

VersionInfo detect_version() {
    VersionInfo out = {};

    char path[MAX_PATH];
    DWORD n = GetModuleFileNameA(NULL, path, MAX_PATH);
    if (n == 0 || n == MAX_PATH) return out;

    const char* base = std::strrchr(path, '\\');
    std::snprintf(out.exe_name, sizeof(out.exe_name), "%s",
                  base ? base + 1 : path);

    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (GetFileAttributesExA(path, GetFileExInfoStandard, &fad)) {
        out.size = (static_cast<uint64_t>(fad.nFileSizeHigh) << 32) |
                   fad.nFileSizeLow;
    }

    DWORD handle = 0;
    DWORD size = GetFileVersionInfoSizeA(path, &handle);
    if (size == 0) return out;
    void* data = HeapAlloc(GetProcessHeap(), 0, size);
    if (!data) return out;

    if (GetFileVersionInfoA(path, handle, size, data)) {
        VS_FIXEDFILEINFO* info = nullptr;
        UINT info_size = 0;
        if (VerQueryValueA(data, "\\",
                           reinterpret_cast<LPVOID*>(&info), &info_size)
            && info && info->dwSignature == 0xFEEF04BD) {
            std::snprintf(out.file_version, sizeof(out.file_version),
                "%u.%u.%u.%u",
                static_cast<unsigned>((info->dwFileVersionMS >> 16) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionMS      ) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionLS >> 16) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionLS      ) & 0xFFFFu));
        }
    }
    HeapFree(GetProcessHeap(), 0, data);
    return out;
}
