// Game-state probes. Hand-rolled minimal reader rather than linking IV-SDK
// into the build, because IV-SDK's headers (a) have no include guards,
// (b) rely on MSVC's deferred name lookup for inline methods, and
// (c) use MSVC-only `__asm {}` naked-function blocks in Hooks.h. We keep
// the IV-SDK submodule as a reference source for the offset numbers below.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

#include "probes.h"

namespace {

enum class GameVersion {
    Unknown,
    V_1_0_7_0,
    V_1_0_8_0,
};

GameVersion g_version = GameVersion::Unknown;
uintptr_t g_base = 0;

// Function RVAs. Sourced from iv-sdk/include/CPlayerInfo.h:
//   CPlayerInfo::GetPlayerInfo = AddressSetter::Get(0x417F20, 0x3CD210)
constexpr uintptr_t kRva_GetPlayerInfo_1070 = 0x417F20;
constexpr uintptr_t kRva_GetPlayerInfo_1080 = 0x3CD210;

// CPlayerInfo field offsets (iv-sdk/include/CPlayerInfo.h, VALIDATE_OFFSET'd)
constexpr uintptr_t kOffs_CPlayerInfo_maxHealth = 0x54A;  // uint16_t
constexpr uintptr_t kOffs_CPlayerInfo_maxArmor  = 0x54C;  // uint16_t
constexpr uintptr_t kOffs_CPlayerInfo_playerPed = 0x58C;  // CPed*

// CPed field offsets (iv-sdk/include/CPed.h)
constexpr uintptr_t kOffs_CPed_pedHealth = 0x214;  // float

uint32_t read_exe_file_version() {
    char path[MAX_PATH];
    if (GetModuleFileNameA(NULL, path, MAX_PATH) == 0) {
        return 0;
    }

    DWORD handle = 0;
    DWORD size = GetFileVersionInfoSizeA(path, &handle);
    if (size == 0) {
        return 0;
    }

    void* data = HeapAlloc(GetProcessHeap(), 0, size);
    if (!data) {
        return 0;
    }

    uint32_t version_number = 0;
    if (GetFileVersionInfoA(path, handle, size, data)) {
        VS_FIXEDFILEINFO* info = nullptr;
        UINT info_size = 0;
        if (VerQueryValueA(data, "\\",
                           reinterpret_cast<LPVOID*>(&info), &info_size)
            && info && info->dwSignature == 0xFEEF04BD) {
            // VS_FIXEDFILEINFO::dwFileVersionMS/LS pack major.minor.build.rev.
            // We only care about distinguishing GTA IV's 1.0.7.0 vs 1.0.8.0;
            // IV-SDK's convention: concat the four numbers decimally so
            // 1.0.7.0 → "1070" and 1.0.8.0 → "1080".
            char buf[32];
            std::snprintf(buf, sizeof(buf), "%u%u%u%u",
                static_cast<unsigned>((info->dwFileVersionMS >> 16) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionMS      ) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionLS >> 16) & 0xFFFFu),
                static_cast<unsigned>((info->dwFileVersionLS      ) & 0xFFFFu));
            version_number = static_cast<uint32_t>(std::strtoul(buf, nullptr, 10));
        }
    }

    HeapFree(GetProcessHeap(), 0, data);
    return version_number;
}

template <typename T>
T read_at(uintptr_t addr) {
    return *reinterpret_cast<T*>(addr);
}

uintptr_t resolve_get_player_info_rva() {
    switch (g_version) {
        case GameVersion::V_1_0_7_0: return kRva_GetPlayerInfo_1070;
        case GameVersion::V_1_0_8_0: return kRva_GetPlayerInfo_1080;
        default:                     return 0;
    }
}

}  // namespace

namespace probes {

const char* init() {
    g_base = reinterpret_cast<uintptr_t>(GetModuleHandle(NULL));

    uint32_t v = read_exe_file_version();
    switch (v) {
        case 1070: g_version = GameVersion::V_1_0_7_0; return "classic 1.0.7.0";
        case 1080: g_version = GameVersion::V_1_0_8_0; return "classic 1.0.8.0";
        default:
            g_version = GameVersion::Unknown;
            // Reported version doesn't match our table. Common cases: Complete
            // Edition 1.2.x, repack rebuilds that strip the version resource,
            // or a build we haven't fingerprinted yet. probes::sample() will
            // short-circuit on Unknown until M8 pins the user's build.
            return "unknown";
    }
}

PlayerState sample() {
    PlayerState out = {};
    uintptr_t rva = resolve_get_player_info_rva();
    if (g_base == 0 || rva == 0) {
        return out;
    }

    using GetPlayerInfoFn = void* (*)(uint32_t);
    auto GetPlayerInfo = reinterpret_cast<GetPlayerInfoFn>(g_base + rva);

    void* info = GetPlayerInfo(0);
    if (!info) {
        return out;
    }
    uintptr_t info_addr = reinterpret_cast<uintptr_t>(info);

    void* ped = read_at<void*>(info_addr + kOffs_CPlayerInfo_playerPed);
    if (!ped) {
        return out;
    }
    uintptr_t ped_addr = reinterpret_cast<uintptr_t>(ped);

    out.valid = true;
    out.health = read_at<float>(ped_addr + kOffs_CPed_pedHealth);
    out.max_health = read_at<uint16_t>(info_addr + kOffs_CPlayerInfo_maxHealth);
    out.max_armor = read_at<uint16_t>(info_addr + kOffs_CPlayerInfo_maxArmor);
    return out;
}

}  // namespace probes
