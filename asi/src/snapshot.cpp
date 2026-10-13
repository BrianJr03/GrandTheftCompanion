#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstring>

#include "snapshot.h"

namespace {

CRITICAL_SECTION g_cs;
bool g_inited = false;

snapshot::Player g_latest{};
bool g_ever_in_game = false;
snapshot::Blips g_blips{};

}  // namespace

namespace snapshot {

void init() {
    if (g_inited) return;
    InitializeCriticalSection(&g_cs);
    g_inited = true;
}

void publish(const Player& p) {
    if (!g_inited) return;
    EnterCriticalSection(&g_cs);
    std::memcpy(&g_latest, &p, sizeof(g_latest));
    if (p.in_game) g_ever_in_game = true;
    LeaveCriticalSection(&g_cs);
}

uint64_t read(Player& out) {
    if (!g_inited) {
        std::memset(&out, 0, sizeof(out));
        return 0;
    }
    EnterCriticalSection(&g_cs);
    std::memcpy(&out, &g_latest, sizeof(out));
    uint64_t ts = g_latest.ts_ms;
    LeaveCriticalSection(&g_cs);
    return ts;
}

bool has_ever_been_in_game() {
    if (!g_inited) return false;
    EnterCriticalSection(&g_cs);
    bool v = g_ever_in_game;
    LeaveCriticalSection(&g_cs);
    return v;
}

void publish_blips(const Blip* list, uint32_t n) {
    if (!g_inited) return;
    if (n > kMaxBlips) n = kMaxBlips;
    EnterCriticalSection(&g_cs);
    const bool same = g_blips.ver != 0 && g_blips.n == n &&
        (n == 0 || std::memcmp(g_blips.list, list, n * sizeof(Blip)) == 0);
    if (!same) {
        if (n) std::memcpy(g_blips.list, list, n * sizeof(Blip));
        g_blips.n = n;
        ++g_blips.ver;
    }
    LeaveCriticalSection(&g_cs);
}

void read_blips(Blips& out) {
    if (!g_inited) {
        std::memset(&out, 0, sizeof(out));
        return;
    }
    EnterCriticalSection(&g_cs);
    std::memcpy(&out, &g_blips, sizeof(out));
    LeaveCriticalSection(&g_cs);
}

uint32_t blips_version() {
    if (!g_inited) return 0;
    EnterCriticalSection(&g_cs);
    uint32_t v = g_blips.ver;
    LeaveCriticalSection(&g_cs);
    return v;
}

}  // namespace snapshot
