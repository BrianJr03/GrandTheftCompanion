// winsock2.h MUST precede windows.h.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "bridge.h"
#include "commands.h"
#include "log.h"
#include "script.h"
#include "shv.h"
#include "snapshot.h"

namespace {

constexpr uint16_t kPort = 47801;
constexpr DWORD kSendIntervalMs = 50;      // 20 Hz
constexpr int kListenBacklog = 2;
// No fresh sample for this long while a save is loaded means the game
// stopped running our script fiber: pause menu, loading, alt-tab.
constexpr uint64_t kStallMs = 1000;
// Big enough for the largest list frame (128 weapons, 64 stations with
// escaped labels, or 96 blips at ~130 bytes each) with room to spare.
constexpr size_t kBufCap = 32 * 1024;

#ifndef THORBRIDGE_LAN_DEBUG
#define THORBRIDGE_LAN_DEBUG 0
#endif

VersionInfo g_vi{};
volatile LONG g_next_client_id = 0;

// ---- tiny append-only JSON writer ------------------------------------

struct Out {
    char*  buf;
    size_t cap;
    size_t len;
    bool   overflow;

    void add(const char* fmt, ...)
#if defined(__GNUC__)
        __attribute__((format(printf, 2, 3)))
#endif
    {
        if (overflow) return;
        va_list ap;
        va_start(ap, fmt);
        int n = std::vsnprintf(buf + len, cap - len, fmt, ap);
        va_end(ap);
        if (n < 0 || static_cast<size_t>(n) >= cap - len) {
            overflow = true;
            return;
        }
        len += static_cast<size_t>(n);
    }

    // JSON string body (no surrounding quotes). Escapes quotes,
    // backslashes and control bytes; everything >= 0x20 passes through,
    // so UTF-8 labels from localised game text survive intact.
    void add_escaped(const char* s) {
        for (; s && *s && !overflow; ++s) {
            const unsigned char c = static_cast<unsigned char>(*s);
            if (c == '"' || c == '\\') {
                add("\\%c", c);
            } else if (c < 0x20) {
                add("\\u%04x", c);
            } else {
                add("%c", c);
            }
        }
    }

    void add_bool(const char* key, bool v) {
        add("\"%s\":%s,", key, v ? "true" : "false");
    }
};

const char* b(bool v) { return v ? "true" : "false"; }

// JSON has no NaN/Inf; a broken float from the game must not produce an
// unparseable frame.
double finite(float f) {
    return (f == f && f > -1e30f && f < 1e30f) ? static_cast<double>(f) : 0.0;
}

int format_hello(char* buf, size_t cap) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"hello\",\"game\":\"v\",\"plugin\":\"%s\",",
          bridge::kPluginVersion);
    o.add("\"exe\":\"");
    o.add_escaped(g_vi.exe_name);
    o.add("\",\"file_version\":\"");
    o.add_escaped(g_vi.file_version);
    o.add("\",\"size\":%llu,", static_cast<unsigned long long>(g_vi.size));
    // "supported" keeps the meaning it has for GTA IV: the plugin can
    // read game state on this install. Here that means Script Hook V
    // is present and exports everything we need.
    o.add("\"supported\":%s,\"shv\":%s,\"script_started\":%s,"
          "\"shv_game_version\":%d,\"cheats\":true,\"err\":\"",
          b(shv::available()), b(shv::available()), b(script::started()),
          shv::game_version());
    o.add_escaped(shv::error());
    o.add("\"}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
}

int format_tick(char* buf, size_t cap) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"tick\",\"game\":\"v\",\"t\":%llu,"
          "\"shv\":%s,\"script_started\":%s}\n",
          static_cast<unsigned long long>(GetTickCount64()),
          b(shv::available()), b(script::started()));
    return o.overflow ? -1 : static_cast<int>(o.len);
}

int format_state(char* buf, size_t cap, const snapshot::State& s,
                 bool stalled) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"state\",\"game\":\"v\",\"t\":%llu,",
          static_cast<unsigned long long>(s.ts_ms));
    o.add_bool("in_game", s.in_game);
    o.add_bool("online", s.online);
    o.add_bool("paused", s.paused);
    o.add_bool("stalled", stalled);
    o.add_bool("switching", s.switching);
    o.add_bool("cutscene", s.cutscene);
    o.add_bool("mission", s.mission);
    o.add_bool("animal", s.animal);
    // LB / L1 / Tab held: the game's weapon wheel is up, so the
    // companion shows its own. (select_held is plugin-internal.)
    o.add_bool("wheel", s.wheel);
    o.add_bool("dpad_down", s.dpad_down_edge);
    o.add("\"character\":%d,\"ped_model\":%u,",
          static_cast<int>(s.character), s.ped_model);

    if (s.money_valid) o.add("\"money\":%d,", s.money);
    o.add("\"health\":%d,\"max_health\":%d,\"armor\":%d,\"max_armor\":%d,"
          "\"wanted\":%u,",
          s.health, s.max_health, s.armor, s.max_armor,
          static_cast<unsigned>(s.wanted));

    o.add("\"special\":{\"unlocked\":%s,\"enabled\":%s,\"active\":%s,"
          "\"full\":%s},",
          b(s.special_unlocked), b(s.special_enabled),
          b(s.special_active), b(s.special_full));

    // "weapon_hash", not "weapon": GTA IV frames use "weapon" for a
    // small signed id, and a joaat hash does not fit a signed 32-bit.
    o.add("\"weapon_hash\":%u,\"weapon_name\":\"", s.weapon);
    o.add_escaped(s.weapon_name);
    o.add("\",\"weapon_group\":%u,\"ammo_total\":%d,\"ammo_clip\":%d,"
          "\"clip_size\":%d,",
          s.weapon_group, s.ammo_total, s.ammo_clip, s.clip_size);

    o.add_bool("in_vehicle", s.in_vehicle);
    if (s.in_vehicle) {
        o.add("\"vehicle\":{\"model\":%u,\"name\":\"", s.vehicle_model);
        o.add_escaped(s.vehicle_name);
        o.add("\",\"class\":%d,\"speed_mps\":%.2f,\"engine_hp\":%.1f,"
              "\"body_hp\":%.1f,\"altitude_m\":%.1f,\"has_radio\":%s,"
              "\"colour1\":%d,\"colour2\":%d,\"custom\":%s},",
              s.vehicle_class, finite(s.vehicle_speed_mps),
              finite(s.vehicle_engine_hp), finite(s.vehicle_body_hp),
              finite(s.vehicle_altitude_m), b(s.vehicle_has_radio),
              s.vehicle_colour1, s.vehicle_colour2, b(s.vehicle_custom));
    }

    o.add("\"radio\":%d,\"radio_name\":\"", s.radio_index);
    o.add_escaped(s.radio_name);
    o.add("\",");

    o.add("\"cheats_mask\":%u,", s.cheats_mask);

    // The player's map waypoint. "ours" means the companion set it (via
    // route_blip) and it has not been moved since, so the companion may
    // move or clear it; a hand-placed one is left alone.
    o.add("\"waypoint\":{\"active\":%s,\"ours\":%s,\"blip\":%d}}\n",
          b(s.waypoint_active), b(s.waypoint_ours), s.route_blip);
    return o.overflow ? -1 : static_cast<int>(o.len);
}

// Raw census of the map's blips, nearest first. Sprite / colour /
// type / display are the game's own enums, uninterpreted: the
// companion decides what to show, and the first on-device runs of this
// frame are how the mission-start sprites get identified.
int format_blips(char* buf, size_t cap, const snapshot::Lists& l) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"blips\",\"game\":\"v\",\"ver\":%u,\"blips\":[",
          l.blips_ver);
    for (uint32_t i = 0; i < l.n_blips; ++i) {
        const snapshot::Blip& bl = l.blips[i];
        o.add("%s{\"id\":%d,\"sprite\":%d,\"colour\":%d,\"type\":%d,"
              "\"display\":%d,\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"dist_m\":%.0f}",
              i ? "," : "", bl.id, bl.sprite, bl.colour, bl.type, bl.display,
              finite(bl.x), finite(bl.y), finite(bl.z), finite(bl.dist_m));
    }
    o.add("]}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
}

int format_weapons(char* buf, size_t cap, const snapshot::Lists& l) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"weapons\",\"game\":\"v\",\"weapons\":[");
    for (uint32_t i = 0; i < l.n_weapons; ++i) {
        const snapshot::Weapon& w = l.weapons[i];
        o.add("%s{\"hash\":%u,\"group\":%u,\"ammo\":%d,\"name\":\"",
              i ? "," : "", w.hash, w.group, w.ammo);
        o.add_escaped(w.name);
        o.add("\"}");
    }
    o.add("]}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
}

// Vehicle-mods catalogue. One frame per `mods` publish; the version
// counter rides on the enclosing Mods struct, so stale frames are
// filtered client-side by comparing `ver`.
int format_mods(char* buf, size_t cap, const snapshot::Mods& m) {
    Out o{buf, cap, 0, false};
    // "vehicle_model" instead of "vehicle" so the frame does not
    // collide with the state frame's nested vehicle object when both
    // land on the same client-side GameState model.
    o.add("{\"v\":1,\"type\":\"mods\",\"game\":\"v\",\"ver\":%u,"
          "\"vehicle_model\":%u,", m.ver, m.vehicle);
    o.add_bool("kit", m.kit);
    o.add("\"wheel_type\":%d,\"tints\":%d,\"tint\":%d,"
          "\"plate_style\":%d,", m.wheel_type, m.tints, m.tint, m.plate_style);
    o.add("\"plate\":\"");
    o.add_escaped(m.plate);
    o.add("\",");
    o.add_bool("neon", m.neon);
    o.add_bool("bulletproof", m.bulletproof);
    o.add("\"slots\":[");
    for (uint32_t i = 0; i < m.n_slots; ++i) {
        const snapshot::ModSlot& s = m.slots[i];
        o.add("%s{\"slot\":%d,\"cur\":%d,\"name\":\"", i ? "," : "", s.slot, s.cur);
        o.add_escaped(s.name);
        o.add("\",\"options\":[");
        for (uint32_t j = 0; j < s.n_options; ++j) {
            o.add("%s\"", j ? "," : "");
            o.add_escaped(s.options[j]);
            o.add("\"");
        }
        o.add("]}");
    }
    o.add("],\"toggles\":[");
    for (uint32_t i = 0; i < m.n_toggles; ++i) {
        const snapshot::ModToggle& t = m.toggles[i];
        o.add("%s{\"slot\":%d,\"on\":%s,\"name\":\"",
              i ? "," : "", t.slot, b(t.on));
        o.add_escaped(t.name);
        o.add("\"}");
    }
    o.add("]}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
}

// One weapon's Ammu-Nation page. The client groups `options` by
// `bone` to render clip / scope / muzzle / etc. slot rows; each
// group's "cur" is the option with `fitted:true`. `widx` = -1 means
// the weapon is owned but has no DLC-catalogue entry — the Tint and
// Ammo sections are hidden on the client in that case.
int format_armory(char* buf, size_t cap, const snapshot::Armory& a) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"armory\",\"game\":\"v\",\"ver\":%u,"
          "\"widx\":%d,\"weapon\":%u,\"name\":\"",
          a.ver, a.widx, a.weapon);
    o.add_escaped(a.name);
    o.add("\",\"tints\":%d,\"tint\":%d,\"ammo\":%d,\"max_ammo\":%d,"
          "\"clip\":%d,\"options\":[",
          a.tints, a.tint, a.ammo, a.max_ammo, a.clip);
    for (uint32_t i = 0; i < a.n_options; ++i) {
        const snapshot::ArmoryOption& op = a.options[i];
        o.add("%s{\"c\":%d,\"bone\":%u,\"default\":%s,\"fitted\":%s,"
              "\"name\":\"",
              i ? "," : "", op.cidx, op.bone,
              b(op.is_default), b(op.is_fitted));
        o.add_escaped(op.name);
        o.add("\"}");
    }
    o.add("]}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
}

int format_stations(char* buf, size_t cap, const snapshot::Lists& l) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"stations\",\"game\":\"v\",\"stations\":[");
    for (uint32_t i = 0; i < l.n_stations; ++i) {
        const snapshot::Station& st = l.stations[i];
        o.add("%s{\"name\":\"", i ? "," : "");
        o.add_escaped(st.name);
        o.add("\",\"label\":\"");
        o.add_escaped(st.label);
        o.add("\"}");
    }
    o.add("]}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
}

int format_ack(char* buf, size_t cap, const commands::Ack& a) {
    Out o{buf, cap, 0, false};
    o.add("{\"v\":1,\"type\":\"ack\",\"game\":\"v\",\"id\":%u,\"ok\":%s,"
          "\"err\":\"", a.id, b(a.ok));
    o.add_escaped(a.err);
    o.add("\"}\n");
    return o.overflow ? -1 : static_cast<int>(o.len);
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

// Minimal extraction for `cmd` frames — same wire format and the same
// approach as the GTA IV plugin: id, name, and the first value inside
// args. Numeric args ride `arg0`; the single string command is
// `set_plate` which carries `{"text":"..."}` and is copied into
// `text` (max 8 chars + NUL, matching the game's plate size and the
// plugin's validator). `text[0]` is set to '\0' on numeric commands.
bool parse_cmd(const char* line, uint32_t* id, char* name, size_t name_cap,
               uint32_t* arg0, char* text, size_t text_cap) {
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
    while (*n && *n != '"' && i + 1 < name_cap) name[i++] = *n++;
    name[i] = '\0';
    if (*n != '"') return false;

    *arg0 = 0;
    if (text_cap) text[0] = '\0';
    const char* a = std::strstr(line, "\"args\":");
    if (!a) return true;   // args optional

    // String branch: "text":"..." before any number inside args.
    const char* t = std::strstr(a, "\"text\":\"");
    if (t && (t < std::strchr(a, '}') || !std::strchr(a, '}'))) {
        t += 8;
        if (!text_cap) return false;
        size_t j = 0;
        while (*t && *t != '"' && j + 1 < text_cap) {
            // Un-escape only the two cases the sender ever produces:
            // \" and \\. Anything else bails the line.
            if (*t == '\\') {
                ++t;
                if (*t == '"' || *t == '\\') { text[j++] = *t++; continue; }
                return false;
            }
            text[j++] = *t++;
        }
        text[j] = '\0';
        if (*t != '"') return false;
        return true;
    }

    while (*a && !(*a >= '0' && *a <= '9')) ++a;
    if (!*a) return true;
    // strtoull, not strtoul: `unsigned long` is 32-bit on Windows and
    // would saturate silently; parse wide, then range-check.
    unsigned long long v = std::strtoull(a, nullptr, 10);
    if (v > 0xFFFFFFFFull) return false;
    *arg0 = static_cast<uint32_t>(v);
    return true;
}

struct ClientCtx {
    SOCKET        sock;
    uint32_t      id;
    volatile LONG alive;
};

DWORD WINAPI reader_main(LPVOID arg) {
    auto* cx = static_cast<ClientCtx*>(arg);
    char buf[1024];
    size_t len = 0;

    while (InterlockedCompareExchange(&cx->alive, 1, 1) == 1) {
        int n = recv(cx->sock, buf + len, static_cast<int>(sizeof(buf) - len), 0);
        if (n <= 0) break;
        len += static_cast<size_t>(n);

        for (;;) {
            char* nl = static_cast<char*>(std::memchr(buf, '\n', len));
            if (!nl) break;
            size_t line_len = static_cast<size_t>(nl - buf);
            buf[line_len] = '\0';

            commands::Cmd c{};
            c.client = cx->id;
            c.queued_ms = GetTickCount64();
            if (parse_cmd(buf, &c.id, c.name, sizeof(c.name), &c.arg0,
                          c.text, sizeof(c.text))) {
                thor_log("[bridge/rx] cmd id=%u name=%s arg0=%u text='%s'",
                         c.id, c.name, c.arg0, c.text);
                commands::enqueue(c);
            } else {
                thor_log("[bridge/rx] unparseable line (%u bytes)",
                         static_cast<unsigned>(line_len));
            }

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

    ClientCtx cx;
    cx.sock = sock;
    cx.id = static_cast<uint32_t>(InterlockedIncrement(&g_next_client_id));
    cx.alive = 1;
    thor_log("[bridge] client %u connected", cx.id);

    // Frames and the list snapshot are too large for a thread stack to
    // carry comfortably; one heap block per connection.
    char* buf = static_cast<char*>(std::malloc(kBufCap));
    auto* lists = static_cast<snapshot::Lists*>(
        std::malloc(sizeof(snapshot::Lists)));
    HANDLE reader = nullptr;
    uint32_t sent_weapons_ver = 0, sent_stations_ver = 0, sent_blips_ver = 0;
    uint32_t sent_mods_ver = 0, sent_armory_ver = 0;
    int n = 0;

    if (!buf || !lists) {
        thor_log("[bridge] out of memory for client %u", cx.id);
        goto done;
    }
    reader = CreateThread(NULL, 0, reader_main, &cx, 0, NULL);
    if (!reader) {
        thor_log("[bridge] reader thread spawn failed");
        goto done;
    }

    n = format_hello(buf, kBufCap);
    if (n <= 0 || !send_all(sock, buf, n)) {
        thor_log("[bridge] hello send failed");
        goto done;
    }

    for (;;) {
        // 1. Acks first — snappier than waiting for the next state frame.
        commands::Ack a;
        while (commands::take_ack(cx.id, &a)) {
            n = format_ack(buf, kBufCap, a);
            if (n <= 0 || !send_all(sock, buf, n)) goto done;
        }

        // 2. Lists, only when they changed since we last sent them.
        uint32_t wv = 0, sv = 0, bv = 0, mv = 0, av = 0;
        snapshot::list_versions(&wv, &sv, &bv, &mv, &av);
        if (wv != sent_weapons_ver || sv != sent_stations_ver ||
            bv != sent_blips_ver || mv != sent_mods_ver ||
            av != sent_armory_ver) {
            snapshot::read_lists(*lists);
            if (lists->weapons_ver != sent_weapons_ver) {
                n = format_weapons(buf, kBufCap, *lists);
                if (n <= 0) {
                    thor_log("[bridge] weapons frame overflow");
                } else if (!send_all(sock, buf, n)) {
                    goto done;
                }
                sent_weapons_ver = lists->weapons_ver;
            }
            if (lists->stations_ver != sent_stations_ver) {
                n = format_stations(buf, kBufCap, *lists);
                if (n <= 0) {
                    thor_log("[bridge] stations frame overflow");
                } else if (!send_all(sock, buf, n)) {
                    goto done;
                }
                sent_stations_ver = lists->stations_ver;
            }
            if (lists->blips_ver != sent_blips_ver) {
                n = format_blips(buf, kBufCap, *lists);
                if (n <= 0) {
                    thor_log("[bridge] blips frame overflow");
                } else if (!send_all(sock, buf, n)) {
                    goto done;
                }
                sent_blips_ver = lists->blips_ver;
            }
            if (lists->mods_ver != sent_mods_ver) {
                n = format_mods(buf, kBufCap, lists->mods);
                if (n <= 0) {
                    thor_log("[bridge] mods frame overflow");
                } else if (!send_all(sock, buf, n)) {
                    goto done;
                }
                sent_mods_ver = lists->mods_ver;
            }
            if (lists->armory_ver != sent_armory_ver) {
                n = format_armory(buf, kBufCap, lists->armory);
                if (n <= 0) {
                    thor_log("[bridge] armory frame overflow");
                } else if (!send_all(sock, buf, n)) {
                    goto done;
                }
                sent_armory_ver = lists->armory_ver;
            }
        }

        // 3. State, or a heartbeat until the script has published once.
        snapshot::State s{};
        const uint64_t ts = snapshot::read(s);
        if (ts != 0) {
            const bool stalled = GetTickCount64() - ts > kStallMs;
            n = format_state(buf, kBufCap, s, stalled);
        } else {
            n = format_tick(buf, kBufCap);
        }
        if (n <= 0) {
            thor_log("[bridge] frame format overflow");
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

done:
    thor_log("[bridge] client %u disconnected", cx.id);
    InterlockedExchange(&cx.alive, 0);
    shutdown(sock, SD_BOTH);
    closesocket(sock);
    if (reader) {
        // closesocket() unblocks the reader's recv(); it only touches
        // cx, which outlives this wait.
        WaitForSingleObject(reader, INFINITE);
        CloseHandle(reader);
    }
    std::free(lists);
    std::free(buf);
    return 0;
}

// Build a fresh listener bound and in LISTEN state, or INVALID_SOCKET
// with the error already logged.
SOCKET open_listener() {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        thor_log("[bridge] socket() failed: %d", WSAGetLastError());
        return INVALID_SOCKET;
    }

    BOOL yes = TRUE;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<const char*>(&yes), sizeof(yes));

    sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kPort);
#if THORBRIDGE_LAN_DEBUG
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
#else
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
#endif

    if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr))
        == SOCKET_ERROR) {
        thor_log("[bridge] bind on port %u failed: %d",
                 kPort, WSAGetLastError());
        closesocket(s);
        return INVALID_SOCKET;
    }

    if (listen(s, kListenBacklog) == SOCKET_ERROR) {
        thor_log("[bridge] listen failed: %d", WSAGetLastError());
        closesocket(s);
        return INVALID_SOCKET;
    }
    return s;
}

DWORD WINAPI server_main(LPVOID /*unused*/) {
    WSADATA wsa;
    int rc = WSAStartup(MAKEWORD(2, 2), &wsa);
    if (rc != 0) {
        thor_log("[bridge] WSAStartup failed: %d", rc);
        return 1;
    }

#if THORBRIDGE_LAN_DEBUG
    thor_log("[bridge] LAN debug build: binding 0.0.0.0:%u", kPort);
#endif

    SOCKET listener = open_listener();
    if (listener == INVALID_SOCKET) {
        WSACleanup();
        return 1;
    }
    thor_log("[bridge] listening on 127.0.0.1:%u", kPort);

    for (;;) {
        SOCKET client = accept(listener, NULL, NULL);
        if (client == INVALID_SOCKET) {
            int err = WSAGetLastError();
            // 10038 (WSAENOTSOCK) and 995 (ERROR_OPERATION_ABORTED) mean
            // the listener handle itself is gone — seen on the AYN Thor
            // Wine prefix shortly after GTA V starts up, where something
            // in the game or anti-tamper path invalidates sockets opened
            // before the engine finishes init. Retrying accept() on a
            // dead handle just prints the same error forever; recycle
            // the listener instead.
            if (err == WSAENOTSOCK || err == 995) {
                thor_log("[bridge] listener invalidated (err=%d), "
                         "rebuilding", err);
                closesocket(listener);
                listener = INVALID_SOCKET;
                for (int attempt = 0; listener == INVALID_SOCKET; ++attempt) {
                    Sleep(attempt < 3 ? 500 : 2000);
                    listener = open_listener();
                }
                thor_log("[bridge] listener restored on 127.0.0.1:%u", kPort);
                continue;
            }
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
