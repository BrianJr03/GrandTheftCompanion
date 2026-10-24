#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cmath>
#include <cstdio>
#include <cstring>

#include "blips.h"
#include "log.h"
#include "natives.h"
#include "shv.h"
#include "snapshot.h"

namespace {

// Census in progress: blips seen so far this pass. Larger than the
// published cap so a crowded map is truncated by distance, not by
// whichever sprites happen to be walked first.
constexpr uint32_t kAccCap = 256;
snapshot::Blip g_acc[kAccCap];
uint32_t g_acc_n = 0;
int32_t  g_next_sprite = 0;          // where the next tick resumes
shv::Vec3 g_player{};                // player position at pass start
uint32_t g_passes = 0;

// What route_to() last set, so the waypoint can be told apart from one
// the player placed by hand.
struct Route {
    int32_t id;
    float   x, y;
};
Route g_route{};

float dist_xy(float ax, float ay, float bx, float by) {
    const float dx = ax - bx, dy = ay - by;
    return std::sqrt(dx * dx + dy * dy);
}

void add_blip(int32_t id, int32_t sprite) {
    if (g_acc_n >= kAccCap) return;
    snapshot::Blip& b = g_acc[g_acc_n];
    std::memset(&b, 0, sizeof(b));   // memcmp-stable padding
    b.id      = id;
    b.sprite  = sprite;
    b.colour  = shv::call<int32_t>(natives::GET_BLIP_COLOUR, id);
    b.type    = shv::call<int32_t>(natives::GET_BLIP_INFO_ID_TYPE, id);
    b.display = shv::call<int32_t>(natives::GET_BLIP_INFO_ID_DISPLAY, id);
    const shv::Vec3 p = shv::call_vec3(natives::GET_BLIP_INFO_ID_COORD, id);
    b.x = p.x;
    b.y = p.y;
    b.z = p.z;
    const float dx = p.x - g_player.x, dy = p.y - g_player.y,
                dz = p.z - g_player.z;
    b.dist_m = std::sqrt(dx * dx + dy * dy + dz * dz);
    ++g_acc_n;
}

// Nearest first. n is small and the list is nearly sorted between
// passes, so insertion sort is plenty.
void sort_by_distance() {
    for (uint32_t i = 1; i < g_acc_n; ++i) {
        snapshot::Blip key = g_acc[i];
        uint32_t j = i;
        while (j > 0 && g_acc[j - 1].dist_m > key.dist_m) {
            g_acc[j] = g_acc[j - 1];
            --j;
        }
        g_acc[j] = key;
    }
}

void finish_pass() {
    sort_by_distance();
    uint32_t n = g_acc_n;
    if (n > snapshot::kMaxBlips) n = static_cast<uint32_t>(snapshot::kMaxBlips);
    snapshot::publish_blips(g_acc, n);
    ++g_passes;
    if (g_passes == 1) {
        thor_log("[blips] first census: %u blip(s) on the map, %u published",
                 g_acc_n, n);
        blips::log_census();
    }
    g_acc_n = 0;
    g_next_sprite = 0;
}

void advance_census(int32_t ped) {
    static int32_t s_waypoint_sprite = -1;
    if (g_next_sprite == 0) {
        g_player = shv::call_vec3(natives::GET_ENTITY_COORDS, ped, 1);
        s_waypoint_sprite = shv::call<int32_t>(natives::GET_WAYPOINT_BLIP_ENUM_ID);
    }
    const int32_t end = g_next_sprite + blips::kSpritesPerTick;
    for (int32_t sprite = g_next_sprite; sprite < end && sprite < blips::kMaxSprite;
         ++sprite) {
        // The waypoint is a blip too, but it is the route, not a place
        // to route to; leaving it out also keeps the published list
        // stable across route_blip / route_clear.
        if (sprite == s_waypoint_sprite) continue;
        int32_t id = shv::call<int32_t>(natives::GET_FIRST_BLIP_INFO_ID, sprite);
        // The walk is bounded by the handle count the game can hand
        // out; the guard is for a NEXT that never returns 0.
        for (int guard = 0; id != 0 && guard < 512; ++guard) {
            if (shv::call_bool(natives::DOES_BLIP_EXIST, id)) add_blip(id, sprite);
            id = shv::call<int32_t>(natives::GET_NEXT_BLIP_INFO_ID, sprite);
        }
    }
    g_next_sprite = end;
    if (g_next_sprite >= blips::kMaxSprite) finish_pass();
}

// A g_route is "set" when either a blip id or an xy pair is recorded.
// route_to_xy stores id = 0 with nonzero coords; route_to stores both.
static bool route_is_set() {
    return g_route.id != 0 || g_route.x != 0.0f || g_route.y != 0.0f;
}

void read_waypoint(snapshot::State& s) {
    s.waypoint_active = shv::call_bool(natives::IS_WAYPOINT_ACTIVE);
    if (!s.waypoint_active) {
        // Gone: the player cleared it or arrived. Either way it is no
        // longer ours to move.
        if (route_is_set()) thor_log("[blips] waypoint gone, route cleared");
        g_route = Route{};
        return;
    }
    if (!route_is_set()) return;
    const int32_t wp_sprite = shv::call<int32_t>(natives::GET_WAYPOINT_BLIP_ENUM_ID);
    const int32_t wp = shv::call<int32_t>(natives::GET_FIRST_BLIP_INFO_ID, wp_sprite);
    if (wp == 0) return;   // active but not a blip yet; keep last answer
    const shv::Vec3 p = shv::call_vec3(natives::GET_BLIP_INFO_ID_COORD, wp);
    s.waypoint_ours = dist_xy(p.x, p.y, g_route.x, g_route.y) <= blips::kOursToleranceM;
    if (!s.waypoint_ours) {
        // The player moved it by hand: hands off from now on.
        thor_log("[blips] waypoint moved by the player, route cleared");
        g_route = Route{};
    }
}

}  // namespace

namespace blips {

void tick(int32_t ped, snapshot::State& s) {
    advance_census(ped);
    read_waypoint(s);
    s.route_blip = g_route.id;
}

bool route_to(int32_t id, char* err, size_t cap) {
    snapshot::Blip b{};
    if (!snapshot::blip_by_id(id, &b)) {
        std::snprintf(err, cap, "blip not in the last scan");
        return false;
    }
    if (!shv::call_bool(natives::DOES_BLIP_EXIST, id)) {
        std::snprintf(err, cap, "blip no longer exists");
        return false;
    }
    // Fresh coordinates: an entity blip may have moved since the scan.
    const shv::Vec3 p = shv::call_vec3(natives::GET_BLIP_INFO_ID_COORD, id);
    shv::call<void>(natives::SET_NEW_WAYPOINT, p.x, p.y);
    g_route = Route{id, p.x, p.y};
    thor_log("[blips] route_blip id=%d sprite=%d -> waypoint at (%.1f, %.1f)",
             id, b.sprite, p.x, p.y);
    return true;
}

void route_to_xy(float x, float y) {
    shv::call<void>(natives::SET_NEW_WAYPOINT, x, y);
    // id 0 is the sentinel for "we routed to a bare point, not a
    // blip". waypoint_ours still matches on x/y distance (the game
    // may snap to a road), and route_clear works the same.
    g_route = Route{0, x, y};
    thor_log("[blips] waypoint_xy -> (%.1f, %.1f)", x, y);
}

void route_clear() {
    shv::call<void>(natives::SET_WAYPOINT_OFF);
    if (g_route.id != 0) {
        thor_log("[blips] route_clear (was blip %d)", g_route.id);
    } else if (g_route.x != 0.0f || g_route.y != 0.0f) {
        thor_log("[blips] route_clear (was xy %.1f, %.1f)", g_route.x, g_route.y);
    }
    g_route = Route{};
}

void log_census() {
    static snapshot::Lists lists;   // ~20 KB: not for the fiber's stack
    snapshot::read_lists(lists);
    thor_log("[blips] census ver=%u n=%u (nearest first)",
             lists.blips_ver, lists.n_blips);
    for (uint32_t i = 0; i < lists.n_blips; ++i) {
        const snapshot::Blip& b = lists.blips[i];
        thor_log("[blips]   id=%d sprite=%d colour=%d type=%d display=%d "
                 "pos=(%.1f, %.1f, %.1f) dist=%.0fm",
                 b.id, b.sprite, b.colour, b.type, b.display,
                 b.x, b.y, b.z, b.dist_m);
    }
}

}  // namespace blips
