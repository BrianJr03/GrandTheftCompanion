#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "snapshot.h"

namespace {

CRITICAL_SECTION g_cs;
bool g_inited = false;

snapshot::State g_state{};
snapshot::Lists g_lists{};

struct Lock {
    Lock()  { EnterCriticalSection(&g_cs); }
    ~Lock() { LeaveCriticalSection(&g_cs); }
};

}  // namespace

namespace snapshot {

void init() {
    if (g_inited) return;
    InitializeCriticalSection(&g_cs);
    g_inited = true;
}

void publish(const State& s) {
    if (!g_inited) return;
    Lock l;
    std::memcpy(&g_state, &s, sizeof(g_state));
}

void publish_weapons(const Weapon* list, uint32_t n) {
    if (!g_inited) return;
    if (n > kMaxWeapons) n = static_cast<uint32_t>(kMaxWeapons);
    Lock l;
    const bool same = g_lists.weapons_ver != 0 && g_lists.n_weapons == n &&
        (n == 0 || std::memcmp(g_lists.weapons, list, n * sizeof(Weapon)) == 0);
    if (same) return;
    if (n) std::memcpy(g_lists.weapons, list, n * sizeof(Weapon));
    g_lists.n_weapons = n;
    ++g_lists.weapons_ver;
}

void publish_stations(const Station* list, uint32_t n) {
    if (!g_inited) return;
    if (n > kMaxStations) n = static_cast<uint32_t>(kMaxStations);
    Lock l;
    const bool same = g_lists.stations_ver != 0 && g_lists.n_stations == n &&
        (n == 0 || std::memcmp(g_lists.stations, list, n * sizeof(Station)) == 0);
    if (same) return;
    if (n) std::memcpy(g_lists.stations, list, n * sizeof(Station));
    g_lists.n_stations = n;
    ++g_lists.stations_ver;
}

void publish_blips(const Blip* list, uint32_t n) {
    if (!g_inited) return;
    if (n > kMaxBlips) n = static_cast<uint32_t>(kMaxBlips);
    Lock l;
    const bool same = g_lists.blips_ver != 0 && g_lists.n_blips == n &&
        (n == 0 || std::memcmp(g_lists.blips, list, n * sizeof(Blip)) == 0);
    if (same) return;
    if (n) std::memcpy(g_lists.blips, list, n * sizeof(Blip));
    g_lists.n_blips = n;
    ++g_lists.blips_ver;
}

uint64_t read(State& out) {
    if (!g_inited) {
        std::memset(&out, 0, sizeof(out));
        return 0;
    }
    Lock l;
    std::memcpy(&out, &g_state, sizeof(out));
    return g_state.ts_ms;
}

void read_lists(Lists& out) {
    if (!g_inited) {
        std::memset(&out, 0, sizeof(out));
        return;
    }
    Lock l;
    std::memcpy(&out, &g_lists, sizeof(out));
}

void list_versions(uint32_t* weapons_ver, uint32_t* stations_ver,
                   uint32_t* blips_ver) {
    if (!g_inited) {
        *weapons_ver = 0;
        *stations_ver = 0;
        *blips_ver = 0;
        return;
    }
    Lock l;
    *weapons_ver = g_lists.weapons_ver;
    *stations_ver = g_lists.stations_ver;
    *blips_ver = g_lists.blips_ver;
}

bool station_name(uint32_t index, char* out, size_t cap) {
    if (!g_inited || cap == 0) return false;
    Lock l;
    if (index >= g_lists.n_stations) return false;
    std::snprintf(out, cap, "%s", g_lists.stations[index].name);
    return out[0] != '\0';
}

bool blip_by_id(int32_t id, Blip* out) {
    if (!g_inited || !out || id == 0) return false;
    Lock l;
    for (uint32_t i = 0; i < g_lists.n_blips; ++i) {
        if (g_lists.blips[i].id != id) continue;
        *out = g_lists.blips[i];
        return true;
    }
    return false;
}

}  // namespace snapshot
