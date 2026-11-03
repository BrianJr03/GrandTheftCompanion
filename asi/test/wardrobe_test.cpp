// Host-side test for src/wardrobe.cpp — the IV side of the Wardrobe
// catalogue walker and command dispatcher. Built with the host C++
// compiler. Every game::* wrapper that wardrobe.cpp calls is faked;
// so is the snapshot:: writer side (publish_wardrobe / clear_wardrobe
// / wardrobe_version) and thor_log. The snapshot's actual critical-
// section implementation lives in snapshot.cpp (Windows-only) and is
// not compiled here — the fake tracks the published state in a plain
// struct.
//
// What this proves: the walker publishes one row per non-empty
// component slot plus one row per prop slot the ped's model
// exposes; set_piece writes the right natives in the right order;
// apply_outfit validates every part before writing, so one bad part
// refuses the whole outfit; reset_outfit clears components and
// props; a ped-model change drops the catalogue. What it cannot
// prove: the hashes resolve on-device, the game actually honours
// the (prop, drawable, texture) tuples we send, or that
// SET_CHAR_PROP_INDEX_TEXTURE's argument order matches our
// prediction — Gate W4 settles those.

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../src/cheat_natives.h"
#include "../src/snapshot.h"
#include "../src/wardrobe.h"

namespace {

int g_failures = 0;
int g_checks   = 0;

void check(bool ok, const char* what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("  FAIL %s\n", what);
    }
}

// Pack a piece the same way the companion does.
uint32_t pack_piece(bool is_prop, uint8_t slot,
                    int32_t drawable, int32_t texture) {
    uint32_t d = (is_prop && drawable < 0)
                 ? 0xFFFu
                 : static_cast<uint32_t>(drawable) & 0xFFFu;
    uint32_t out = 0;
    if (is_prop) out |= (1u << 24);
    out |= (static_cast<uint32_t>(slot) & 0xFu) << 20;
    out |= (d & 0xFFFu) << 8;
    out |= static_cast<uint32_t>(texture) & 0xFFu;
    return out;
}

// ---- fake game ------------------------------------------------------

constexpr uint32_t kPed      = 0x1001;
constexpr uint32_t kNikoHash = 0xDEADBEEFu;   // arbitrary; the real one is joaat("player")

struct FakeGame {
    uint32_t ped_model = kNikoHash;
    // Component drawable + texture counts. Slot 2 is empty (zero
    // drawables) so the walker should skip it.
    //   slot 1 upper   : 3 drawables, textures {2, 1, 4}
    //   slot 2 lower   : 0 (skipped)
    //   slot 3 suse    : 2 drawables, textures {2, 3}
    //   slot 4 hand    : 1 drawable,  textures {5}
    int32_t comp_cur[11] = {0};
    int32_t comp_tex[11] = {0};
    int32_t prop_cur[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
    int set_comp_calls = 0;
    int set_prop_index_calls = 0;
    int set_prop_tex_calls = 0;
    int clear_prop_calls = 0;
    int clear_all_props_calls = 0;
    int default_variation_calls = 0;

    uint32_t comp_drawables(uint32_t slot) const {
        switch (slot) {
            case 1: return 3;
            case 2: return 0;
            case 3: return 2;
            case 4: return 1;
            default: return 0;
        }
    }
    uint32_t comp_textures(uint32_t slot, uint32_t d) const {
        switch (slot) {
            case 1: return d == 0 ? 2 : d == 1 ? 1 : d == 2 ? 4 : 0;
            case 3: return d == 0 ? 2 : d == 1 ? 3 : 0;
            case 4: return d == 0 ? 5 : 0;
            default: return 0;
        }
    }
};
FakeGame g;

void fake_reset() { g = FakeGame{}; }

// ---- fake snapshot ---------------------------------------------------

namespace fake_snap {
snapshot::Wardrobe last{};
bool have_last = false;
uint32_t ver = 0;
int publish_calls = 0;
int clear_calls = 0;

void reset_tracking() {
    std::memset(&last, 0, sizeof(last));
    have_last = false;
    ver = 0;
    publish_calls = 0;
    clear_calls = 0;
}
}

// ---- fake log --------------------------------------------------------

std::vector<std::string> g_log;

}  // namespace

// --- game:: fakes (link overrides) -----------------------------------
namespace game {

uint32_t get_char_model(uint32_t ped) {
    (void)ped;
    return g.ped_model;
}
uint32_t get_char_drawable_variation(uint32_t ped, uint32_t component) {
    (void)ped;
    return component < 11 ? static_cast<uint32_t>(g.comp_cur[component]) : 0;
}
uint32_t get_char_texture_variation(uint32_t ped, uint32_t component) {
    (void)ped;
    return component < 11 ? static_cast<uint32_t>(g.comp_tex[component]) : 0;
}
uint32_t get_number_of_char_drawable_variations(uint32_t ped, uint32_t component) {
    (void)ped;
    return g.comp_drawables(component);
}
uint32_t get_number_of_char_texture_variations(uint32_t ped, uint32_t component,
                                               uint32_t drawable) {
    (void)ped;
    return g.comp_textures(component, drawable);
}
void set_char_default_component_variation(uint32_t ped) {
    (void)ped;
    for (int i = 0; i < 11; ++i) { g.comp_cur[i] = 0; g.comp_tex[i] = 0; }
    ++g.default_variation_calls;
}
void set_char_component_variation(uint32_t ped, uint32_t component,
                                  uint32_t drawable, uint32_t texture) {
    (void)ped;
    if (component < 11) {
        g.comp_cur[component] = static_cast<int32_t>(drawable);
        g.comp_tex[component] = static_cast<int32_t>(texture);
    }
    ++g.set_comp_calls;
}
void set_char_prop_index(uint32_t ped, uint32_t prop_type, uint32_t index) {
    (void)ped;
    if (prop_type < 8) g.prop_cur[prop_type] = static_cast<int32_t>(index);
    ++g.set_prop_index_calls;
}
void set_char_prop_index_texture(uint32_t ped, uint32_t prop_type,
                                 uint32_t index, uint32_t texture) {
    (void)ped;
    (void)prop_type;
    (void)index;
    (void)texture;
    ++g.set_prop_tex_calls;
}
uint32_t get_char_prop_index(uint32_t ped, uint32_t prop_type) {
    (void)ped;
    if (prop_type >= 8) return 0xFFFFFFFFu;
    return g.prop_cur[prop_type] < 0 ? 0xFFFFFFFFu
                                     : static_cast<uint32_t>(g.prop_cur[prop_type]);
}
void clear_char_prop(uint32_t ped, uint32_t prop_type) {
    (void)ped;
    if (prop_type < 8) g.prop_cur[prop_type] = -1;
    ++g.clear_prop_calls;
}
void clear_all_char_props(uint32_t ped) {
    (void)ped;
    for (int i = 0; i < 8; ++i) g.prop_cur[i] = -1;
    ++g.clear_all_props_calls;
}

// Not called by the wardrobe walker, but linked because snapshot.h /
// cheat_natives.h declares them. Stubs are enough.
uint32_t get_player_id() { return 0; }
uint32_t get_car_char_is_using(uint32_t) { return 0; }
void set_char_health(uint32_t, uint32_t) {}
void add_armour_to_char(uint32_t, uint32_t) {}
uint32_t get_max_wanted_level() { return 6; }
void alter_wanted_level(uint32_t, uint32_t) {}
void apply_wanted_level_change_now(uint32_t) {}
void clear_wanted_level(uint32_t) {}
void give_weapon_to_char(uint32_t, uint32_t, uint32_t) {}
void set_char_ammo(uint32_t, uint32_t, uint32_t) {}
void fix_car(uint32_t) {}
void wash_vehicle_textures(uint32_t, uint32_t) {}
void change_car_colour(uint32_t, uint32_t, uint32_t) {}
void get_car_colors(uint32_t, uint32_t*, uint32_t*) {}
void force_weather_now(uint32_t) {}
void release_weather() {}
void get_time_of_day(uint32_t*, uint32_t*) {}
void set_time_of_day(uint32_t, uint32_t) {}
void set_player_invincible(uint32_t, bool) {}
void set_player_fast_reload(uint32_t, bool) {}
void set_player_never_gets_tired(uint32_t, bool) {}
void set_time_scale(float) {}
void set_police_ignore_player(uint32_t, bool) {}
uint32_t get_hash_key(const char*) { return 0; }
void request_model(uint32_t) {}
bool has_model_loaded(uint32_t) { return false; }
void mark_model_as_no_longer_needed(uint32_t) {}
void create_car(uint32_t, float, float, float, uint32_t*) {}
void warp_char_into_car(uint32_t, uint32_t) {}
void mark_car_as_no_longer_needed(uint32_t*) {}
void set_char_heading(uint32_t, float) {}

}  // namespace game

// --- snapshot:: fakes ------------------------------------------------
namespace snapshot {

void init() {}

void publish_wardrobe(const Wardrobe& w) {
    std::memcpy(&fake_snap::last, &w, sizeof(w));
    ++fake_snap::ver;
    fake_snap::last.ver = fake_snap::ver;
    fake_snap::have_last = true;
    ++fake_snap::publish_calls;
}

void clear_wardrobe() {
    std::memset(&fake_snap::last, 0, sizeof(fake_snap::last));
    ++fake_snap::ver;
    fake_snap::last.ver = fake_snap::ver;
    fake_snap::have_last = false;
    ++fake_snap::clear_calls;
}

void read_wardrobe(Wardrobe& out) {
    std::memcpy(&out, &fake_snap::last, sizeof(out));
}

uint32_t wardrobe_version() { return fake_snap::ver; }

// Pure stubs — the walker never calls these but the linker needs
// them because snapshot.h declares them in the same TU.
void publish(const Player&) {}
uint64_t read(Player& out) {
    std::memset(&out, 0, sizeof(out));
    return 0;
}
bool has_ever_been_in_game() { return false; }
void publish_blips(const Blip*, uint32_t) {}
void read_blips(Blips& out) { std::memset(&out, 0, sizeof(out)); }
uint32_t blips_version() { return 0; }

}  // namespace snapshot

// --- thor_log stub ---------------------------------------------------

void thor_log(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    g_log.push_back(buf);
}

namespace {

// --- helpers ---------------------------------------------------------

// Advance the walker until it finishes; the walker reads
// kSlotsPerTick per advance so ~a handful of ticks finishes the ~10
// slot catalogue.
void drain_walker(int32_t episode) {
    for (int i = 0; i < 20 && !fake_snap::have_last; ++i) {
        wardrobe::tick(kPed, episode);
    }
}

char g_err[64];

// --- tests -----------------------------------------------------------

void test_walker_publishes_non_empty_slots() {
    std::printf("walker publishes non-empty slots\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);
    check(fake_snap::have_last, "catalogue was published");
    const snapshot::Wardrobe& w = fake_snap::last;
    check(w.episode == 0, "episode == 0 (Niko)");
    check(w.ped_model == kNikoHash, "ped_model matches walker read");

    // Find each slot in the published list.
    auto find = [&](uint8_t slot, bool prop) -> const snapshot::WardrobeSlot* {
        for (uint32_t i = 0; i < w.n_slots; ++i) {
            const snapshot::WardrobeSlot& s = w.slots[i];
            if (s.slot == slot && s.prop == prop) return &s;
        }
        return nullptr;
    };
    const snapshot::WardrobeSlot* s1 = find(1, false);
    const snapshot::WardrobeSlot* s2 = find(2, false);
    const snapshot::WardrobeSlot* s3 = find(3, false);
    const snapshot::WardrobeSlot* s4 = find(4, false);
    check(s1 != nullptr, "component slot 1 (upper) published");
    check(s2 == nullptr, "component slot 2 (zero drawables) skipped");
    check(s3 != nullptr, "component slot 3 (suse) published");
    check(s4 != nullptr, "component slot 4 (hand) published");
    if (s1) {
        check(s1->styles == 3, "slot 1 styles == 3");
        check(s1->colors[0] == 2 && s1->colors[1] == 1 && s1->colors[2] == 4,
              "slot 1 per-drawable texture counts");
    }
    const snapshot::WardrobeSlot* hat = find(0, true);
    const snapshot::WardrobeSlot* glasses = find(1, true);
    check(hat != nullptr, "prop slot 0 (hat) published");
    check(glasses != nullptr, "prop slot 1 (glasses) published");
    if (hat) {
        check(hat->cur == -1, "hat starts cleared (cur == -1)");
        // IV has no prop texture count — walker fills with zeros so
        // the companion probes.
        check(hat->colors[0] == 0, "prop colors left at zero");
    }
}

void test_set_piece_component() {
    std::printf("set_piece component\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);

    // Valid: slot 1, drawable 2, texture 3.
    const bool ok = wardrobe::apply(
        "set_piece", pack_piece(false, 1, 2, 3),
        nullptr, 0, kPed, /*in_vehicle=*/false, 0,
        g_err, sizeof(g_err));
    check(ok, "set_piece slot 1 draw 2 tex 3 -> ok");
    check(g.set_comp_calls == 1, "SET_CHAR_COMPONENT_VARIATION fired once");
    check(g.comp_cur[1] == 2, "mock state: slot 1 cur == 2");
    check(g.comp_tex[1] == 3, "mock state: slot 1 tex == 3");
}

void test_set_piece_prop_and_clear() {
    std::printf("set_piece prop + clear\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);

    // Prop (hat) draw 0 tex 0 is accepted even though colors[] is 0:
    // IV's prop texture count is unknown. We assume the piece writer
    // tries index 0.
    const bool ok1 = wardrobe::apply(
        "set_piece", pack_piece(true, 0, 0, 0),
        nullptr, 0, kPed, false, 0,
        g_err, sizeof(g_err));
    check(ok1, "set_piece hat draw 0 tex 0 -> ok");
    check(g.set_prop_index_calls == 1, "SET_CHAR_PROP_INDEX fired");

    // Clear.
    const bool ok2 = wardrobe::apply(
        "set_piece", pack_piece(true, 0, -1, 0),
        nullptr, 0, kPed, false, 0,
        g_err, sizeof(g_err));
    check(ok2, "set_piece hat clear -> ok");
    check(g.clear_prop_calls == 1, "CLEAR_CHAR_PROP fired on prop clear");
}

void test_set_piece_bad_texture() {
    std::printf("set_piece bad texture\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);

    // Slot 1, drawable 0 has only 2 textures (0, 1). Texture 2 is OOR.
    const bool ok = wardrobe::apply(
        "set_piece", pack_piece(false, 1, 0, 2),
        nullptr, 0, kPed, false, 0,
        g_err, sizeof(g_err));
    check(!ok, "out-of-range texture refused");
    check(std::strstr(g_err, "bad piece") != nullptr,
          "err == 'bad piece'");
    check(g.set_comp_calls == 0, "no native was written");
}

void test_apply_outfit_atomic() {
    std::printf("apply_outfit happy path\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);

    const uint32_t parts[] = {
        pack_piece(false, 1, 0, 0),   // upper
        pack_piece(false, 3, 1, 2),   // suse
        pack_piece(true,  0, 1, 0),   // hat
    };
    const bool ok = wardrobe::apply(
        "apply_outfit", 0,
        parts, 3, kPed, false, 0,
        g_err, sizeof(g_err));
    check(ok, "apply_outfit (3 parts) -> ok");
    check(g.set_comp_calls == 2, "two component writes");
    check(g.set_prop_index_calls == 1, "one prop write");
    check(g.comp_cur[1] == 0 && g.comp_cur[3] == 1,
          "components reflect the outfit");
}

void test_apply_outfit_refuses_on_bad_part() {
    std::printf("apply_outfit refuses on bad part\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);

    const uint32_t parts[] = {
        pack_piece(false, 1, 0, 0),   // ok
        pack_piece(false, 1, 99, 0),  // drawable OOR
        pack_piece(false, 3, 0, 0),   // would be ok but we never get here
    };
    const int32_t cur_before[] = {
        g.comp_cur[1], g.comp_cur[3], g.comp_tex[1], g.comp_tex[3],
    };
    const bool ok = wardrobe::apply(
        "apply_outfit", 0,
        parts, 3, kPed, false, 0,
        g_err, sizeof(g_err));
    check(!ok, "outfit refused");
    check(std::strstr(g_err, "bad part 1") != nullptr,
          "err points at the bad part index ('bad part 1')");
    check(g.set_comp_calls == 0,
          "NO component writes happened — all-or-nothing");
    check(g.comp_cur[1] == cur_before[0] && g.comp_cur[3] == cur_before[1],
          "component state unchanged after refusal");
}

void test_reset_outfit() {
    std::printf("reset_outfit\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);

    // Dirty the state first so the reset actually has something to do.
    g.comp_cur[1] = 2;
    g.comp_tex[1] = 1;
    g.prop_cur[0] = 1;

    const bool ok = wardrobe::apply(
        "reset_outfit", 0, nullptr, 0, kPed, false, 0,
        g_err, sizeof(g_err));
    check(ok, "reset_outfit -> ok");
    check(g.default_variation_calls == 1,
          "SET_CHAR_DEFAULT_COMPONENT_VARIATION fired once");
    check(g.clear_all_props_calls == 1,
          "CLEAR_ALL_CHAR_PROPS fired once");
    check(g.comp_cur[1] == 0,
          "components default-reset in the fake");
    check(g.prop_cur[0] == -1, "props cleared in the fake");
}

void test_in_vehicle_refused() {
    std::printf("in-vehicle refusal\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);
    const bool ok = wardrobe::apply(
        "set_piece", pack_piece(false, 1, 0, 0),
        nullptr, 0, kPed, /*in_vehicle=*/true, 0,
        g_err, sizeof(g_err));
    check(!ok, "set_piece refused in-vehicle");
    check(std::strstr(g_err, "in vehicle") != nullptr,
          "err mentions 'in vehicle'");
}

void test_ped_model_change_drops_catalogue() {
    std::printf("ped-model change drops catalogue\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    wardrobe::request();
    drain_walker(0);
    check(fake_snap::have_last, "catalogue up before change");

    g.ped_model = 0xBAADF00Du;   // "switched" to a different ped
    // One more tick should notice and clear.
    wardrobe::tick(kPed, 0);
    check(fake_snap::clear_calls >= 1,
          "clear_wardrobe fired on model change");
}

void test_wardrobe_list_without_catalogue() {
    std::printf("wardrobe_list arms a walk\n");
    fake_reset();
    fake_snap::reset_tracking();
    wardrobe::reset();
    const bool ok = wardrobe::apply(
        "wardrobe_list", 0, nullptr, 0, kPed, false, 0,
        g_err, sizeof(g_err));
    check(ok, "wardrobe_list -> ok even before any walk");
    drain_walker(0);
    check(fake_snap::have_last, "walk completed after the list ack");
}

}  // namespace

int main() {
    test_walker_publishes_non_empty_slots();
    test_set_piece_component();
    test_set_piece_prop_and_clear();
    test_set_piece_bad_texture();
    test_apply_outfit_atomic();
    test_apply_outfit_refuses_on_bad_part();
    test_reset_outfit();
    test_in_vehicle_refused();
    test_ped_model_change_drops_catalogue();
    test_wardrobe_list_without_catalogue();
    std::printf("\n%d checks, %d failed\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}
