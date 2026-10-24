// winsock2.h MUST precede windows.h — the Windows SDK pulls winsock1 in
// from <windows.h> otherwise and conflicting definitions break the build.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "bridge.h"
#include "commands.h"
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

// Per-client state passed to the reader + writer threads.
struct ClientCtx {
    SOCKET            sock;
    commands::AckQueue ack_queue;
    volatile LONG     alive;   // 1 while both threads should run
};

int format_hello(char* buf, size_t cap) {
#if THORBRIDGE_CHEATS
    constexpr const char* kCheats = "true";
#else
    constexpr const char* kCheats = "false";
#endif
    return std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"hello\","
        "\"crc32\":\"0x%08X\",\"size\":%u,"
        "\"file_version\":\"%s\",\"supported\":%s,"
        "\"cheats\":%s}\n",
        g_vi.crc32, g_vi.size,
        g_vi.file_version[0] ? g_vi.file_version : "",
        g_vi.supported ? "true" : "false",
        kCheats);
}

int format_tick(char* buf, size_t cap, unsigned long t) {
    return std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"tick\",\"t\":%lu}\n", t);
}

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

    // Paint section: colour pair for the Paint UI (THORBRIDGE_CHEATS
    // only). Rendered as two extra fields inside the vehicle block
    // when the probes read fired this frame; omitted otherwise. ~28
    // bytes against the 1 KB stack buffer.
    char colours[64] = {0};
    if (p.vehicle_colours_valid) {
        std::snprintf(colours, sizeof(colours),
                      ",\"colour1\":%d,\"colour2\":%d",
                      p.vehicle_colour1, p.vehicle_colour2);
    }

    // Nine scanned slots at up to ~32 bytes each.
    char weapons[512];
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
        "\"t\":%llu,\"in_game\":%s,\"in_vehicle\":%s,\"paused\":%s,"
        "\"wheel\":%s,\"dpad_down\":%s,"
        "\"money\":%u,\"health\":%.1f,\"max_health\":%u,"
        "\"armor\":%u,\"max_armor\":%u,\"wanted\":%u,"
        "\"weapon\":%d,\"ammo_total\":%d,\"ammo_clip\":%d,"
        "\"owned\":%s,"
        "\"vehicle\":{\"model\":%u,\"name\":\"%s\","
        "\"speed_mps\":%.2f,\"engine_hp\":%.1f%s},"
        "\"radio\":%d,\"episode\":%u,"
        "\"mission\":%s,\"route_blip\":%d,"
        "\"cheats_mask\":%u}\n",
        static_cast<unsigned long long>(p.ts_ms),
        p.in_game ? "true" : "false",
        p.in_vehicle ? "true" : "false",
        p.paused ? "true" : "false",
        p.wheel ? "true" : "false",
        p.dpad_down_edge ? "true" : "false",
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
        colours,
        p.radio_station, p.episode,
        p.mission ? "true" : "false", p.route_blip,
        p.cheats_mask);
}

// Raw census of the map's blips, nearest first (natives level 5; an
// empty list otherwise). Sprite / colour / type / display are the
// game's own enums, uninterpreted: the first on-device runs of this
// frame are how the mission-start sprites get identified.
int format_blips(char* buf, size_t cap, const snapshot::Blips& bl) {
    size_t w = 0;
    int n = std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"blips\",\"ver\":%u,\"blips\":[", bl.ver);
    if (n < 0 || static_cast<size_t>(n) >= cap) return -1;
    w = static_cast<size_t>(n);
    for (uint32_t i = 0; i < bl.n; ++i) {
        const snapshot::Blip& b = bl.list[i];
        n = std::snprintf(buf + w, cap - w,
            "%s{\"id\":%d,\"sprite\":%d,\"colour\":%d,\"type\":%d,"
            "\"display\":%d,\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"dist_m\":%.0f}",
            i ? "," : "", b.id, b.sprite, b.colour, b.type, b.display,
            static_cast<double>(b.x), static_cast<double>(b.y),
            static_cast<double>(b.z), static_cast<double>(b.dist_m));
        if (n < 0 || w + static_cast<size_t>(n) + 3 >= cap) return -1;
        w += static_cast<size_t>(n);
    }
    n = std::snprintf(buf + w, cap - w, "]}\n");
    if (n < 0 || w + static_cast<size_t>(n) >= cap) return -1;
    return static_cast<int>(w + static_cast<size_t>(n));
}

int format_ack(char* buf, size_t cap, const commands::Ack& a) {
    char escaped[128];
    json_escape(escaped, sizeof(escaped), a.err);
    return std::snprintf(buf, cap,
        "{\"v\":1,\"type\":\"ack\",\"id\":%u,\"ok\":%s,\"err\":\"%s\"}\n",
        a.id, a.ok ? "true" : "false", escaped);
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

// Minimal JSON extraction for `cmd` frames. The protocol is small and
// server-side: {"v":1,"type":"cmd","id":<n>,"name":"<name>","args":{...}}.
// We extract id, name, and the first numeric arg under args (weapon or
// station — both commands take one int). More complex args would want
// a real parser.
bool parse_cmd(const char* line, uint32_t* id, char* name, size_t name_cap,
               uint32_t* arg0) {
    if (!std::strstr(line, "\"type\":\"cmd\"")) return false;

    const char* p = std::strstr(line, "\"id\":");
    if (!p) return false;
    char* end = nullptr;
    unsigned long parsed_id = std::strtoul(p + 5, &end, 10);
    if (end == p + 5) return false;
    *id = static_cast<uint32_t>(parsed_id);

    const char* n = std::strstr(line, "\"name\":\"");
    if (!n) return false;
    n += 8;
    size_t i = 0;
    while (*n && *n != '"' && i + 1 < name_cap) {
        name[i++] = *n++;
    }
    name[i] = '\0';
    if (*n != '"') return false;

    // Pull the first integer inside args:{...}. Works for {"weapon":N}
    // and {"station":N} and future single-int commands; richer commands
    // would get a proper parser.
    const char* a = std::strstr(line, "\"args\":");
    if (!a) { *arg0 = 0; return true; }  // args optional
    // Find first digit after args:
    while (*a && !(*a >= '0' && *a <= '9') && *a != '-') ++a;
    if (!*a) { *arg0 = 0; return true; }
    unsigned long parsed_arg = std::strtoul(a, nullptr, 10);
    *arg0 = static_cast<uint32_t>(parsed_arg);
    return true;
}

// Reader thread — one per client. Pulls newline-delimited input and
// enqueues parsed commands. Exits when the socket is closed by the
// writer side.
DWORD WINAPI reader_main(LPVOID arg) {
    auto* cx = static_cast<ClientCtx*>(arg);
    char buf[1024];
    size_t len = 0;

    while (InterlockedCompareExchange(&cx->alive, 1, 1) == 1) {
        int n = recv(cx->sock, buf + len, static_cast<int>(sizeof(buf) - len), 0);
        if (n <= 0) break;
        len += static_cast<size_t>(n);

        // Pull out complete lines.
        for (;;) {
            char* nl = static_cast<char*>(std::memchr(buf, '\n', len));
            if (!nl) break;
            size_t line_len = static_cast<size_t>(nl - buf);
            buf[line_len] = '\0';

            commands::Cmd c{};
            c.ack_queue = &cx->ack_queue;
            c.queued_ms = GetTickCount();
            if (parse_cmd(buf, &c.id, c.name, sizeof(c.name), &c.arg0)) {
                thor_log("[bridge/rx] cmd id=%u name=%s arg0=%u",
                         c.id, c.name, c.arg0);
                commands::enqueue(c);
            } else {
                thor_log("[bridge/rx] unparseable line (%zu bytes)", line_len);
            }

            // Shift any bytes after the newline to the front.
            size_t rem = len - (line_len + 1);
            std::memmove(buf, nl + 1, rem);
            len = rem;
        }

        if (len == sizeof(buf)) {
            thor_log("[bridge/rx] line too long, dropping buffer");
            len = 0;
        }
    }
    return 0;
}

DWORD WINAPI serve_client(LPVOID arg) {
    SOCKET sock = reinterpret_cast<SOCKET>(arg);
    thor_log("[bridge] client connected");

    ClientCtx cx;
    cx.sock = sock;
    cx.alive = 1;

    HANDLE reader = CreateThread(NULL, 0, reader_main, &cx, 0, NULL);
    if (!reader) {
        thor_log("[bridge] reader thread spawn failed");
        closesocket(sock);
        return 0;
    }

    char buf[1024];
    int n = format_hello(buf, sizeof(buf));
    if (n <= 0 || !send_all(sock, buf, n)) {
        thor_log("[bridge] hello send failed");
    }

    // The blip list is too big for the stack-sized frame buffer (64
    // entries at ~130 bytes); one heap block per connection.
    constexpr size_t kBlipBufCap = 12 * 1024;
    char* blip_buf = static_cast<char*>(std::malloc(kBlipBufCap));
    auto* blips = static_cast<snapshot::Blips*>(std::malloc(sizeof(snapshot::Blips)));
    uint32_t sent_blips_ver = 0;

    for (;;) {
        // 1. Flush any pending acks first — snappier perceived latency
        //    than letting them wait for the next 50ms state frame.
        commands::Ack a;
        bool wrote_ack = false;
        while (cx.ack_queue.pop(&a)) {
            n = format_ack(buf, sizeof(buf), a);
            if (n <= 0 || !send_all(sock, buf, n)) goto done;
            wrote_ack = true;
        }

        // 2. Blip list, only when the census changed since we sent it.
        if (blip_buf && blips && snapshot::blips_version() != sent_blips_ver) {
            snapshot::read_blips(*blips);
            n = format_blips(blip_buf, kBlipBufCap, *blips);
            if (n <= 0) {
                thor_log("[bridge] blips frame overflow");
            } else if (!send_all(sock, blip_buf, n)) {
                goto done;
            }
            sent_blips_ver = blips->ver;
        }

        // 3. State or tick frame.
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
        (void)wrote_ack;
        Sleep(kSendIntervalMs);
    }
done:
    thor_log("[bridge] client disconnected");
    InterlockedExchange(&cx.alive, 0);
    shutdown(sock, SD_BOTH);
    closesocket(sock);
    WaitForSingleObject(reader, 2000);
    CloseHandle(reader);
    std::free(blips);
    std::free(blip_buf);
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
