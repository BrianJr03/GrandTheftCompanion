// winsock2.h MUST come before windows.h — the Windows SDK pulls in winsock1
// unconditionally from <windows.h> otherwise and conflicting definitions
// break the build.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdint>
#include <cstdio>

#include "bridge.h"
#include "log.h"
#include "probes.h"

namespace {

constexpr uint16_t kPort = 47801;
constexpr DWORD kSendIntervalMs = 50;  // 20 Hz
constexpr int kListenBacklog = 2;

// Build with -DTHORBRIDGE_LAN_DEBUG=1 to bind 0.0.0.0 for off-device
// smoke-testing from a laptop on the same LAN. Default (loopback-only) is
// what ships.
#ifndef THORBRIDGE_LAN_DEBUG
#define THORBRIDGE_LAN_DEBUG 0
#endif

int format_frame(char* buf, size_t cap, const probes::PlayerState& s) {
    unsigned long t = static_cast<unsigned long>(GetTickCount());
    if (!s.valid) {
        return std::snprintf(buf, cap,
            "{\"t\":%lu,\"valid\":false}\n", t);
    }
    return std::snprintf(buf, cap,
        "{\"t\":%lu,\"valid\":true,"
        "\"health\":%.1f,\"max_health\":%u,\"max_armor\":%u}\n",
        t, static_cast<double>(s.health),
        static_cast<unsigned>(s.max_health),
        static_cast<unsigned>(s.max_armor));
}

DWORD WINAPI serve_client(LPVOID arg) {
    SOCKET sock = reinterpret_cast<SOCKET>(arg);
    thor_log("[bridge] client connected");

    char buf[256];
    for (;;) {
        probes::PlayerState state = probes::sample();
        int n = format_frame(buf, sizeof(buf), state);
        if (n <= 0 || static_cast<size_t>(n) >= sizeof(buf)) {
            thor_log("[bridge] format_frame overflow (n=%d)", n);
            break;
        }
        int sent = send(sock, buf, n, 0);
        if (sent == SOCKET_ERROR) {
            int err = WSAGetLastError();
            // These two are "client went away" — expected on normal
            // disconnect, no need to spam the log.
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

    // Rebind cleanly after a game crash — otherwise the port lingers in
    // TIME_WAIT and we fail on the next launch.
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
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // 127.0.0.1
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

    // Unreachable; left for symmetry.
}

}  // namespace

namespace bridge {

void start() {
    HANDLE th = CreateThread(NULL, 0, server_main, NULL, 0, NULL);
    if (th) {
        CloseHandle(th);
    } else {
        thor_log("[bridge] failed to spawn server thread");
    }
}

}  // namespace bridge
