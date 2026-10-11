// winsock2.h MUST precede windows.h — the Windows SDK pulls winsock1 in
// from <windows.h> otherwise and conflicting definitions break the build.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdint>
#include <cstdio>

#include "bridge.h"
#include "log.h"
#include "snapshot.h"

namespace {

constexpr uint16_t kPort = 47801;
constexpr DWORD kSendIntervalMs = 50;  // 20 Hz
constexpr int kListenBacklog = 2;

#ifndef THORBRIDGE_LAN_DEBUG
#define THORBRIDGE_LAN_DEBUG 0
#endif

VersionInfo g_vi{};

int format_hello(char* buf, size_t cap) {
    return std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"hello\","
        "\"crc32\":\"0x%08X\",\"size\":%u,"
        "\"file_version\":\"%s\",\"supported\":%s}\n",
        g_vi.crc32, g_vi.size,
        g_vi.file_version[0] ? g_vi.file_version : "",
        g_vi.supported ? "true" : "false");
}

int format_tick(char* buf, size_t cap, unsigned long t) {
    return std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"tick\",\"t\":%lu}\n", t);
}

// Escape double-quotes and backslashes in a short game-provided string
// so it's safe to drop into a JSON string literal. Writes at most
// `cap-1` chars + NUL. Game strings are static text-table entries and
// have never been observed to contain control characters, so we don't
// bother escaping those.
void json_escape(char* dst, size_t cap, const char* src) {
    size_t i = 0;
    for (; src && *src && i + 2 < cap; ++src) {
        if (*src == '"' || *src == '\\') {
            dst[i++] = '\\';
            dst[i++] = *src;
        } else {
            dst[i++] = *src;
        }
    }
    dst[i] = '\0';
}

int format_state(char* buf, size_t cap, const snapshot::Player& p) {
    char veh_name[64] = {0};
    json_escape(veh_name, sizeof(veh_name), p.vehicle_name);

    // Owned-weapon arrays: emit only non-zero slots as compact pairs.
    char weapons[256];
    {
        size_t w = 0;
        weapons[w++] = '[';
        bool first = true;
        for (int s = 0; s < 13; ++s) {
            if (p.owned_weapons[s] == 0) continue;
            int n = std::snprintf(weapons + w, sizeof(weapons) - w,
                                  "%s{\"slot\":%d,\"id\":%d,\"ammo\":%d}",
                                  first ? "" : ",",
                                  s, p.owned_weapons[s], p.owned_ammo[s]);
            if (n < 0 || w + static_cast<size_t>(n) + 2 >= sizeof(weapons)) {
                break;
            }
            w += n;
            first = false;
        }
        if (w + 1 < sizeof(weapons)) weapons[w++] = ']';
        weapons[w] = '\0';
    }

    return std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"state\","
        "\"t\":%llu,\"in_game\":%s,\"in_vehicle\":%s,"
        "\"money\":%u,\"health\":%.1f,\"max_health\":%u,"
        "\"armor\":%u,\"max_armor\":%u,\"wanted\":%u,"
        "\"weapon\":%d,\"ammo_total\":%d,\"ammo_clip\":%d,"
        "\"owned\":%s,"
        "\"vehicle\":{\"model\":%u,\"name\":\"%s\","
        "\"speed_mps\":%.2f,\"engine_hp\":%.1f},"
        "\"radio\":%d}\n",
        static_cast<unsigned long long>(p.ts_ms),
        p.in_game ? "true" : "false",
        p.in_vehicle ? "true" : "false",
        p.money,
        static_cast<double>(p.health),
        static_cast<unsigned>(p.max_health),
        static_cast<unsigned>(p.armor),
        static_cast<unsigned>(p.max_armor),
        static_cast<unsigned>(p.wanted_level),
        p.weapon_id, p.ammo_total, p.ammo_clip,
        weapons,
        p.vehicle_model, veh_name,
        static_cast<double>(p.vehicle_speed_mps),
        static_cast<double>(p.vehicle_engine_hp),
        p.radio_station);
}

bool send_all(SOCKET sock, const char* buf, int n) {
    int sent = 0;
    while (sent < n) {
        int w = send(sock, buf + sent, n - sent, 0);
        if (w == SOCKET_ERROR) return false;
        sent += w;
    }
    return true;
}

DWORD WINAPI serve_client(LPVOID arg) {
    SOCKET sock = reinterpret_cast<SOCKET>(arg);
    thor_log("[bridge] client connected");

    char buf[1024];
    int n = format_hello(buf, sizeof(buf));
    if (n <= 0 || !send_all(sock, buf, n)) {
        thor_log("[bridge] hello send failed");
        closesocket(sock);
        return 0;
    }

    for (;;) {
        // Prefer a `state` frame when the game thread has published at
        // least one snapshot; else fall back to a `tick` so the client
        // can tell the ASI is alive.
        snapshot::Player p{};
        uint64_t snap_ts = snapshot::read(p);
        if (snap_ts != 0) {
            n = format_state(buf, sizeof(buf), p);
        } else {
            n = format_tick(buf, sizeof(buf),
                            static_cast<unsigned long>(GetTickCount()));
        }
        if (n <= 0 || static_cast<size_t>(n) >= sizeof(buf)) {
            thor_log("[bridge] frame format overflow (n=%d)", n);
            break;
        }
        if (!send_all(sock, buf, n)) {
            int err = WSAGetLastError();
            if (err != WSAECONNRESET && err != WSAECONNABORTED
                && err != WSAESHUTDOWN) {
                thor_log("[bridge] send failed: %d", err);
            }
            break;
        }
        Sleep(kSendIntervalMs);
    }

    thor_log("[bridge] client disconnected");
    closesocket(sock);
    return 0;
}

DWORD WINAPI server_main(LPVOID /*unused*/) {
    WSADATA wsa;
    int rc = WSAStartup(MAKEWORD(2, 2), &wsa);
    if (rc != 0) {
        thor_log("[bridge] WSAStartup failed: %d", rc);
        return 1;
    }

    SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listener == INVALID_SOCKET) {
        thor_log("[bridge] socket() failed: %d", WSAGetLastError());
        WSACleanup();
        return 1;
    }

    BOOL yes = TRUE;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<const char*>(&yes), sizeof(yes));

    sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kPort);
#if THORBRIDGE_LAN_DEBUG
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    thor_log("[bridge] LAN debug build: binding 0.0.0.0:%u", kPort);
#else
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
#endif

    if (bind(listener, reinterpret_cast<sockaddr*>(&addr), sizeof(addr))
        == SOCKET_ERROR) {
        thor_log("[bridge] bind on port %u failed: %d",
                 kPort, WSAGetLastError());
        closesocket(listener);
        WSACleanup();
        return 1;
    }

    if (listen(listener, kListenBacklog) == SOCKET_ERROR) {
        thor_log("[bridge] listen failed: %d", WSAGetLastError());
        closesocket(listener);
        WSACleanup();
        return 1;
    }

    thor_log("[bridge] listening on 127.0.0.1:%u", kPort);

    for (;;) {
        SOCKET client = accept(listener, NULL, NULL);
        if (client == INVALID_SOCKET) {
            int err = WSAGetLastError();
            thor_log("[bridge] accept failed: %d — retrying in 1s", err);
            Sleep(1000);
            continue;
        }

        HANDLE th = CreateThread(NULL, 0, serve_client,
                                 reinterpret_cast<LPVOID>(client), 0, NULL);
        if (th) {
            CloseHandle(th);
        } else {
            thor_log("[bridge] CreateThread failed, dropping client");
            closesocket(client);
        }
    }
}

}  // namespace

namespace bridge {

void start(const VersionInfo& vi) {
    g_vi = vi;
    HANDLE th = CreateThread(NULL, 0, server_main, NULL, 0, NULL);
    if (th) {
        CloseHandle(th);
    } else {
        thor_log("[bridge] failed to spawn server thread");
    }
}

}  // namespace bridge
