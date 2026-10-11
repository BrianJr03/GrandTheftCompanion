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

namespace {

constexpr uint16_t kPort = 47801;
constexpr DWORD kSendIntervalMs = 50;  // 20 Hz
constexpr int kListenBacklog = 2;

// Build with -DTHORBRIDGE_LAN_DEBUG=1 to bind 0.0.0.0 for off-device
// smoke-testing. Default (loopback-only) is what ships.
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

    char buf[256];
    int n = format_hello(buf, sizeof(buf));
    if (n <= 0 || !send_all(sock, buf, n)) {
        thor_log("[bridge] hello send failed");
        closesocket(sock);
        return 0;
    }

    for (;;) {
        n = format_tick(buf, sizeof(buf),
                        static_cast<unsigned long>(GetTickCount()));
        if (n <= 0 || static_cast<size_t>(n) >= sizeof(buf)) {
            thor_log("[bridge] tick format overflow (n=%d)", n);
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
