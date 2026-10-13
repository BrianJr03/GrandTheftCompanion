#include "blips.h"

#include <cmath>
#include <cstring>

#include "blip_natives.h"
#include "log.h"

namespace {

snapshot::Blip g_list[snapshot::kMaxBlips];
uint32_t g_n = 0;
bool     g_scanned = false;
uint32_t g_scan_ms = 0;
int32_t  g_route = 0;    // blip the route is on, 0 = none

// Nearest first; n is small and nearly sorted between scans.
void sort_by_distance() {
    for (uint32_t i = 1; i < g_n; ++i) {
        snapshot::Blip key = g_list[i];
        uint32_t j = i;
        while (j > 0 && g_list[j - 1].dist_m > key.dist_m) {
            g_list[j] = g_list[j - 1];
            --j;
        }
        g_list[j] = key;
    }
}

// Keep the list at the cap by dropping the farthest entry.
void add(const snapshot::Blip& b) {
    if (g_n < snapshot::kMaxBlips) {
        g_list[g_n++] = b;
        return;
    }
    uint32_t far = 0;
    for (uint32_t i = 1; i < g_n; ++i) {
        if (g_list[i].dist_m > g_list[far].dist_m) far = i;
    }
    if (b.dist_m < g_list[far].dist_m) g_list[far] = b;
}

void scan(uint32_t ped) {
    float px = 0.0f, py = 0.0f, pz = 0.0f;
    game::get_char_coordinates(ped, &px, &py, &pz);
    g_n = 0;
    for (uint32_t sprite = 0; sprite < blips::kMaxSprite; ++sprite) {
        int32_t id = game::get_first_blip_info_id(sprite);
        for (int guard = 0; id != 0 && guard < blips::kWalkGuard; ++guard) {
            if (game::does_blip_exist(id)) {
                snapshot::Blip b;
                std::memset(&b, 0, sizeof(b));   // memcmp-stable padding
                b.id      = id;
                b.sprite  = static_cast<int32_t>(game::get_blip_sprite(id));
                b.colour  = static_cast<int32_t>(game::get_blip_colour(id));
                b.type    = static_cast<int32_t>(game::get_blip_info_id_type(id));
                b.display = static_cast<int32_t>(game::get_blip_info_id_display(id));
                game::get_blip_coords(id, &b.x, &b.y, &b.z);
                const float dx = b.x - px, dy = b.y - py, dz = b.z - pz;
                b.dist_m = std::sqrt(dx * dx + dy * dy + dz * dz);
                add(b);
            }
            id = game::get_next_blip_info_id(sprite);
        }
    }
    sort_by_distance();
}

const snapshot::Blip* find(int32_t id) {
    if (id == 0) return nullptr;
    for (uint32_t i = 0; i < g_n; ++i) {
        if (g_list[i].id == id) return &g_list[i];
    }
    return nullptr;
}

}  // namespace

namespace blips {

bool read(uint32_t ped, uint32_t now_ms, snapshot::Player& p) {
    p.mission = game::get_mission_flag();
    bool scanned = false;
    // Unsigned subtraction: correct across a GetTickCount wrap.
    if (!g_scanned || now_ms - g_scan_ms >= kScanMs) {
        scanned = true;
        const bool first = !g_scanned;
        scan(ped);
        g_scanned = true;
        g_scan_ms = now_ms;
        if (first) {
            thor_log("[blips] first census: %u blip(s)", g_n);
            log_census();
        }
        if (g_route != 0 && !find(g_route)) {
            // The blip went away (mission started, contact moved on):
            // the game dropped its route with it.
            thor_log("[blips] routed blip %d is gone, route cleared", g_route);
            g_route = 0;
        }
    }
    p.route_blip = g_route;
    return scanned;
}

const snapshot::Blip* list(uint32_t* n) {
    *n = g_n;
    return g_list;
}

const char* route_to(int32_t id) {
    const snapshot::Blip* b = find(id);
    if (!b) return "blip not in the last scan";
    if (!game::does_blip_exist(id)) return "blip no longer exists";
    if (g_route != 0 && g_route != id && game::does_blip_exist(g_route)) {
        game::set_route(g_route, false);
    }
    game::set_route(id, true);
    g_route = id;
    thor_log("[blips] route_blip id=%d sprite=%d -> route on", id, b->sprite);
    return nullptr;
}

void route_clear() {
    if (g_route != 0 && game::does_blip_exist(g_route)) {
        game::set_route(g_route, false);
    }
    if (g_route != 0) thor_log("[blips] route_clear (was blip %d)", g_route);
    g_route = 0;
}

void log_census() {
    thor_log("[blips] census n=%u (nearest first)", g_n);
    for (uint32_t i = 0; i < g_n; ++i) {
        const snapshot::Blip& b = g_list[i];
        thor_log("[blips]   id=%d sprite=%d colour=%d type=%d display=%d "
                 "pos=(%.1f, %.1f, %.1f) dist=%.0fm",
                 b.id, b.sprite, b.colour, b.type, b.display,
                 static_cast<double>(b.x), static_cast<double>(b.y),
                 static_cast<double>(b.z), static_cast<double>(b.dist_m));
    }
}

void reset() {
    g_n = 0;
    g_scanned = false;
    g_scan_ms = 0;
    g_route = 0;
}

}  // namespace blips
