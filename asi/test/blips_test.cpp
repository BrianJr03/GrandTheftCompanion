// Host-side test for src/blips.cpp, the GTA IV blip census and GPS
// route behind the companion's "route to mission". Built with the
// machine's own C++ compiler and run directly, like weapons_test.cpp.
//
// The natives blips.cpp calls (blip_natives.h) are replaced by a fake
// map. The fake enforces the contract the real natives are believed to
// have, and fails the test when it is broken:
//   * GET_NEXT_BLIP_INFO_ID only continues a walk GET_FIRST started for
//     the same type.
//   * Per-blip reads are only made for handles the walk handed out and
//     that still exist.
//   * SET_ROUTE is only called on blips that exist.
//
// What this proves: the walk covers every type and stops, the list is
// nearest first and capped, the rescan clock, route on/off bookkeeping
// and the refusals. What it cannot prove: that the real natives behave
// like the fake, or that the marshalling in blip_natives.cpp is right.
// That needs the game.

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "../src/blip_natives.h"
#include "../src/blips.h"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool ok, const char* what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("  FAIL %s\n", what);
    }
}

// ---- fake game -------------------------------------------------------

constexpr uint32_t kPed = 0x1001;
constexpr int kMapCap = 100;

struct FakeBlip {
    int32_t  id;
    uint32_t type;
    uint32_t sprite;
    uint32_t colour;
    float    x, y, z;
    bool     exists;
    bool     route;
};
FakeBlip g_map[kMapCap];
int      g_map_n = 0;
float    g_px = 0.0f, g_py = 0.0f, g_pz = 0.0f;
bool     g_mission = false;

uint32_t g_walk_type = 0xFFFFFFFFu;   // type the current walk is on
int      g_walk_pos = 0;
int      g_first_calls = 0;
int      g_next_calls = 0;
int      g_route_calls = 0;
int      g_logs = 0;
uint32_t g_types_walked = 0;           // bit per type FIRST was called with

FakeBlip* fake_find(int32_t id) {
    for (int i = 0; i < g_map_n; ++i) {
        if (g_map[i].id == id) return &g_map[i];
    }
    return nullptr;
}

void fake_reset() {
    g_map_n = 0;
    g_px = g_py = g_pz = 0.0f;
    g_mission = false;
    g_walk_type = 0xFFFFFFFFu;
    g_walk_pos = 0;
    g_first_calls = g_next_calls = g_route_calls = g_logs = 0;
    g_types_walked = 0;
    blips::reset();
}

void put(int32_t id, uint32_t type, uint32_t sprite, float x, float y) {
    FakeBlip& b = g_map[g_map_n++];
    b = FakeBlip{id, type, sprite, 1, x, y, 10.0f, true, false};
}

// Niko near Roman's cab depot: a few contacts, a coord blip, a car.
void city() {
    put(10, 5, 44, 300.0f, 400.0f);   // contact, 500 m
    put(11, 5, 12, 30.0f, 40.0f);     // contact, 50 m
    put(12, 4, 2, 3000.0f, 4000.0f);  // coord, 5000 m
    put(13, 1, 0, 60.0f, 80.0f);      // car, 100 m
}

int32_t walk_next() {
    int seen = 0;
    for (int i = 0; i < g_map_n; ++i) {
        if (g_map[i].type != g_walk_type || !g_map[i].exists) continue;
        if (seen++ == g_walk_pos) {
            ++g_walk_pos;
            return g_map[i].id;
        }
    }
    return 0;
}

}  // namespace

namespace game {

int32_t get_first_blip_info_id(uint32_t type) {
    ++g_first_calls;
    if (type < 32) g_types_walked |= 1u << type;
    g_walk_type = type;
    g_walk_pos = 0;
    return walk_next();
}

int32_t get_next_blip_info_id(uint32_t type) {
    ++g_next_calls;
    check(type == g_walk_type, "NEXT continues the walk FIRST started");
    return walk_next();
}

bool does_blip_exist(int32_t blip) {
    FakeBlip* b = fake_find(blip);
    return b && b->exists;
}

uint32_t get_blip_sprite(int32_t blip) {
    FakeBlip* b = fake_find(blip);
    check(b && b->exists, "sprite read only for a live blip");
    return b ? b->sprite : 0;
}

uint32_t get_blip_info_id_display(int32_t blip) {
    check(does_blip_exist(blip), "display read only for a live blip");
    return 2;
}

uint32_t get_blip_info_id_type(int32_t blip) {
    FakeBlip* b = fake_find(blip);
    check(b && b->exists, "type read only for a live blip");
    return b ? b->type : 0;
}

uint32_t get_blip_colour(int32_t blip) {
    FakeBlip* b = fake_find(blip);
    check(b && b->exists, "colour read only for a live blip");
    return b ? b->colour : 0;
}

bool get_blip_coords(int32_t blip, float* x, float* y, float* z) {
    FakeBlip* b = fake_find(blip);
    check(b && b->exists, "coords read only for a live blip");
    check(x && y && z, "coords: all three out-pointers supplied");
    if (!b || !x || !y || !z) return false;
    *x = b->x;
    *y = b->y;
    *z = b->z;
    return true;
}

bool get_char_coordinates(uint32_t ped, float* x, float* y, float* z) {
    check(ped == kPed, "player coords asked for the player ped");
    *x = g_px;
    *y = g_py;
    *z = g_pz;
    return true;
}

bool get_mission_flag() { return g_mission; }

void set_route(int32_t blip, bool on) {
    ++g_route_calls;
    FakeBlip* b = fake_find(blip);
    check(b && b->exists, "SET_ROUTE only on a live blip");
    if (b) b->route = on;
}

}  // namespace game

void thor_log(const char* /*fmt*/, ...) { ++g_logs; }

// ---- tests -----------------------------------------------------------

namespace {

void test_census() {
    std::printf("census\n");
    fake_reset();
    city();
    snapshot::Player p{};
    blips::read(kPed, 1000, p);

    uint32_t n = 0;
    const snapshot::Blip* l = blips::list(&n);
    check(n == 4, "all four blips found");
    check(n == 4 && l[0].id == 11 && l[1].id == 13 && l[2].id == 10 && l[3].id == 12,
          "nearest first");
    check(n == 4 && l[0].sprite == 12 && l[0].type == 5 && l[0].colour == 1,
          "sprite / type / colour carried through");
    check(n == 4 && l[0].x == 30.0f && l[0].y == 40.0f && l[0].z == 10.0f,
          "coordinates carried through");
    check(n == 4 && l[0].dist_m > 50.9f && l[0].dist_m < 51.1f,
          "distance is 3-D from the player");
    check(g_first_calls == static_cast<int>(blips::kMaxType) + 1,
          "one FIRST per type 0..kMaxType");
    check((g_types_walked & 0x3Fu) == 0x3Fu, "types 0..5 were walked");
    // Per type with k blips: k-1 NEXTs to reach the rest, one more to
    // hit the 0 that ends the walk.
    check(g_next_calls == 4, "NEXT called k times for a type with k blips");
    check(p.route_blip == 0 && !p.mission, "no route, no mission");
    check(g_logs >= 5, "first census is logged (header + one line per blip)");
}

void test_rescan_clock() {
    std::printf("rescan clock\n");
    fake_reset();
    city();
    snapshot::Player p{};
    blips::read(kPed, 1000, p);
    const int first = g_first_calls;
    blips::read(kPed, 1000 + blips::kScanMs - 1, p);
    check(g_first_calls == first, "no rescan before kScanMs");
    blips::read(kPed, 1000 + blips::kScanMs, p);
    check(g_first_calls == 2 * first, "rescan at kScanMs");
    // Tick wrap: the clock going backwards numerically must still count.
    blips::read(kPed, 0xFFFFFF00u, p);
    blips::read(kPed, 0xFFFFFF00u + blips::kScanMs, p);
    check(g_first_calls == 4 * first, "rescan across a tick wrap");
}

void test_cap() {
    std::printf("cap\n");
    fake_reset();
    // 74 blips at 10000, 9900, ... 2700 m, nearest ones added last so
    // the cap has to replace, not just truncate. The ten farthest
    // (10000..9100 m) must be the ones missing. (Blips sit 10 m above
    // the player, so 3-D distances run a hair over the round numbers.)
    for (int i = 0; i < static_cast<int>(snapshot::kMaxBlips) + 10; ++i) {
        const float d = 10000.0f - 100.0f * static_cast<float>(i);
        put(100 + i, 4, 1, d, 0.0f);
    }
    snapshot::Player p{};
    blips::read(kPed, 1000, p);
    uint32_t n = 0;
    const snapshot::Blip* l = blips::list(&n);
    check(n == snapshot::kMaxBlips, "list capped");
    bool sorted = true, nearest_kept = true;
    for (uint32_t i = 1; i < n; ++i) sorted &= l[i - 1].dist_m <= l[i].dist_m;
    for (uint32_t i = 0; i < n; ++i) nearest_kept &= l[i].dist_m < 9001.0f;
    check(sorted, "capped list still nearest first");
    check(nearest_kept, "the farthest blips are the ones dropped");
}

void test_route() {
    std::printf("route\n");
    fake_reset();
    city();
    snapshot::Player p{};
    blips::read(kPed, 1000, p);

    check(blips::route_to(999) != nullptr, "unknown id refused");
    check(g_route_calls == 0, "refusal touches no route");
    check(blips::route_to(10) == nullptr, "listed blip accepted");
    check(fake_find(10)->route, "route on for the blip");
    blips::read(kPed, 1001, p);
    check(p.route_blip == 10, "state reports the routed blip");

    check(blips::route_to(11) == nullptr, "switching target accepted");
    check(!fake_find(10)->route && fake_find(11)->route,
          "old route off, new route on");
    const int calls = g_route_calls;
    check(blips::route_to(11) == nullptr && g_route_calls == calls + 1,
          "re-routing to the same blip does not switch it off first");

    blips::route_clear();
    check(!fake_find(11)->route, "route_clear turns the route off");
    blips::read(kPed, 1002, p);
    check(p.route_blip == 0, "state reports no route after clear");
    blips::route_clear();
    check(g_route_calls == calls + 2, "a second clear is a no-op");

    // A blip that vanished between the scan and the tap.
    check(blips::route_to(12) == nullptr, "route to a coord blip");
    fake_find(12)->exists = false;
    check(blips::route_to(12) != nullptr, "vanished blip refused");
    // ...and the route is dropped at the next rescan without touching
    // the dead handle.
    const int rc = g_route_calls;
    blips::read(kPed, 1000 + blips::kScanMs, p);
    check(p.route_blip == 0, "route forgotten once its blip is gone");
    check(g_route_calls == rc, "no SET_ROUTE on a dead blip");
}

void test_mission_flag() {
    std::printf("mission flag\n");
    fake_reset();
    city();
    snapshot::Player p{};
    g_mission = true;
    blips::read(kPed, 1000, p);
    check(p.mission, "mission flag read every frame");
    g_mission = false;
    blips::read(kPed, 1001, p);
    check(!p.mission, "mission flag clears");
}

void test_empty_map() {
    std::printf("empty map\n");
    fake_reset();
    snapshot::Player p{};
    blips::read(kPed, 1000, p);
    uint32_t n = 0;
    blips::list(&n);
    check(n == 0, "no blips, no entries");
    check(g_next_calls == 0, "NEXT never called when FIRST answers 0");
    check(blips::route_to(1) != nullptr, "nothing to route to");
}

}  // namespace

int main() {
    test_census();
    test_rescan_clock();
    test_cap();
    test_route();
    test_mission_flag();
    test_empty_map();
    std::printf("%d checks, %d failure(s)\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}
