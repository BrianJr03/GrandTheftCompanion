#include <cstdio>
#include <cstring>

#include "cheat_natives.h"
#include "log.h"
#include "snapshot.h"
#include "wardrobe.h"

// IV side of the Wardrobe feature. Logic mirrors asi-v/src/wardrobe
// but routes every game call through game::<wrapper> so this file is
// Windows-free and host-testable, same split pattern cheats.cpp uses.

namespace {

// Component slots the walker is willing to publish, in walk order.
// FusionFix / iv-sdk names (prediction):
//   0 head, 1 upper, 2 lower, 3 suse, 4 hand, 5 feet,
//   6 jacket, 7 hair, 8 suse2, 9 teeth, 10 face.
// 0 (head), 9 (teeth) and 10 (face) are hidden — face edits are not
// a wardrobe action. The walker also skips any slot that reports
// zero drawables for the current ped, so a Director Mode ped that
// uses a different slot set self-filters.
constexpr uint8_t kComponentWalkOrder[] = { 1, 2, 3, 4, 5, 6, 7, 8 };
constexpr size_t kComponentWalkCount =
    sizeof(kComponentWalkOrder) / sizeof(kComponentWalkOrder[0]);

// Prop slots. Niko, Johnny and Luis each use at most these two;
// anything beyond is Director Mode territory and is not published.
constexpr uint8_t kPropWalkOrder[] = { 0, 1 };
constexpr size_t kPropWalkCount =
    sizeof(kPropWalkOrder) / sizeof(kPropWalkOrder[0]);

constexpr uint32_t kWalkTotal =
    static_cast<uint32_t>(kComponentWalkCount + kPropWalkCount);

// Wire packing. Same layout as asi-v/src/wardrobe.cpp's unpack_piece
// so the companion sends identical frames to both plugins.
constexpr uint32_t kBitProp   = 1u << 24;
constexpr uint32_t kSlotMask  = 0xFu;
constexpr uint32_t kSlotShift = 20u;
constexpr uint32_t kDrawMask  = 0xFFFu;
constexpr uint32_t kDrawShift = 8u;
constexpr uint32_t kTexMask   = 0xFFu;

struct Piece {
    bool    is_prop;
    uint8_t slot;
    int32_t drawable;   // -1 on a prop clear
    int32_t texture;
};

Piece unpack_piece(uint32_t v) {
    Piece p{};
    p.is_prop  = (v & kBitProp) != 0;
    p.slot     = static_cast<uint8_t>((v >> kSlotShift) & kSlotMask);
    const uint32_t d = (v >> kDrawShift) & kDrawMask;
    p.drawable = (p.is_prop && d == wardrobe::kClearProp)
                 ? -1
                 : static_cast<int32_t>(d);
    p.texture  = static_cast<int32_t>(v & kTexMask);
    return p;
}

struct Walker {
    bool              in_progress;
    bool              requested;
    uint32_t          ped;
    uint32_t          ped_model;
    int8_t            episode;
    uint32_t          next_slot;    // index into [0, kWalkTotal)
    snapshot::Wardrobe out;
};
Walker g_w{};

snapshot::Wardrobe g_last{};
bool g_have_last = false;

void publish_now() {
    snapshot::publish_wardrobe(g_w.out);
    std::memcpy(&g_last, &g_w.out, sizeof(g_last));
    g_have_last = true;
}

void start_walk(uint32_t ped, int32_t episode) {
    std::memset(&g_w.out, 0, sizeof(g_w.out));
    g_w.in_progress = true;
    g_w.ped = ped;
    g_w.next_slot = 0;
    g_w.ped_model = game::get_char_model(ped);
    g_w.out.ped_model = g_w.ped_model;
    g_w.out.episode = static_cast<int8_t>(episode);
    g_w.episode = g_w.out.episode;

    thor_log("[wardrobe] walk start ped=%u model=0x%08X episode=%d",
             ped, g_w.ped_model, static_cast<int>(g_w.out.episode));
}

bool is_component_index(uint32_t walk_idx) {
    return walk_idx < kComponentWalkCount;
}

uint8_t slot_for_walk_index(uint32_t walk_idx, bool* is_prop) {
    if (is_component_index(walk_idx)) {
        *is_prop = false;
        return kComponentWalkOrder[walk_idx];
    }
    *is_prop = true;
    return kPropWalkOrder[walk_idx - kComponentWalkCount];
}

void read_component_slot(uint32_t ped, uint8_t slot) {
    const uint32_t drawables =
        game::get_number_of_char_drawable_variations(ped, slot);
    if (drawables == 0) return;
    if (g_w.out.n_slots >= snapshot::kMaxWardrobeSlots) {
        thor_log("[wardrobe] WARN component slot %u dropped: kMaxWardrobeSlots",
                 static_cast<unsigned>(slot));
        return;
    }
    snapshot::WardrobeSlot& out = g_w.out.slots[g_w.out.n_slots++];
    std::memset(&out, 0, sizeof(out));
    out.slot = slot;
    out.prop = false;
    out.cur = static_cast<int32_t>(game::get_char_drawable_variation(ped, slot));
    out.tex = static_cast<int32_t>(game::get_char_texture_variation(ped, slot));
    out.pal = 0;   // IV has no palette; stays zero
    uint32_t cap = drawables;
    if (cap > snapshot::kMaxWardrobeStyles) {
        thor_log("[wardrobe] WARN component %u has %u styles; clamped to %u",
                 static_cast<unsigned>(slot), cap,
                 static_cast<unsigned>(snapshot::kMaxWardrobeStyles));
        cap = snapshot::kMaxWardrobeStyles;
    }
    out.styles = cap;
    for (uint32_t d = 0; d < cap; ++d) {
        const uint32_t tex =
            game::get_number_of_char_texture_variations(ped, slot, d);
        out.colors[d] = static_cast<uint16_t>(
            tex > 0xFFFFu ? 0xFFFFu : tex);
    }
}

// Props do not have a drawable-count native on IV. The walker tries
// drawables 0..styles-1 by writing and reading back — but that would
// change the ped's look during a catalogue walk, which is awful. For
// v1 we publish prop slots with styles = a conservative 32 (the real
// game's actual counts; adjust from the gate log) and colours left at
// zero, meaning "unknown". The companion probes a prop's colours by
// trying the next texture and keeping it only if the read-back moved.
constexpr uint32_t kDefaultPropStyles = 32;

void read_prop_slot(uint32_t ped, uint8_t slot) {
    if (g_w.out.n_slots >= snapshot::kMaxWardrobeSlots) {
        thor_log("[wardrobe] WARN prop slot %u dropped: kMaxWardrobeSlots",
                 static_cast<unsigned>(slot));
        return;
    }
    snapshot::WardrobeSlot& out = g_w.out.slots[g_w.out.n_slots++];
    std::memset(&out, 0, sizeof(out));
    out.slot = slot;
    out.prop = true;
    const uint32_t raw = game::get_char_prop_index(ped, slot);
    // The game returns 0xFFFFFFFF for "not fitted"; map to -1.
    out.cur = (raw == 0xFFFFFFFFu) ? -1 : static_cast<int32_t>(raw);
    out.tex = 0;   // no getter for prop texture on IV; starts at 0
    out.pal = 0;
    out.styles = kDefaultPropStyles;
    // colors[] stays zero — see comment above kDefaultPropStyles.
}

bool advance() {
    if (!g_w.in_progress || g_w.ped == 0) return false;
    const uint32_t end = g_w.next_slot + wardrobe::kSlotsPerTick;
    for (uint32_t i = g_w.next_slot; i < end && i < kWalkTotal; ++i) {
        bool is_prop = false;
        const uint8_t slot = slot_for_walk_index(i, &is_prop);
        if (is_prop) read_prop_slot(g_w.ped, slot);
        else         read_component_slot(g_w.ped, slot);
    }
    g_w.next_slot = end;
    if (g_w.next_slot >= kWalkTotal) {
        thor_log("[wardrobe] walk done ped=%u slots=%u",
                 g_w.ped, g_w.out.n_slots);
        g_w.in_progress = false;
        g_w.requested = false;
        publish_now();
        return true;
    }
    return false;
}

void refresh_all(uint32_t ped) {
    if (!g_have_last) return;
    snapshot::Wardrobe& m = g_last;
    for (uint32_t i = 0; i < m.n_slots; ++i) {
        snapshot::WardrobeSlot& s = m.slots[i];
        if (s.prop) {
            const uint32_t raw = game::get_char_prop_index(ped, s.slot);
            s.cur = (raw == 0xFFFFFFFFu) ? -1 : static_cast<int32_t>(raw);
            // No getter for prop texture; the companion mirrors the
            // last write it made via its own tap.
        } else {
            s.cur = static_cast<int32_t>(
                game::get_char_drawable_variation(ped, s.slot));
            s.tex = static_cast<int32_t>(
                game::get_char_texture_variation(ped, s.slot));
        }
    }
    snapshot::publish_wardrobe(m);
}

bool slot_in_catalogue(uint8_t slot, bool is_prop,
                       uint32_t* styles_out) {
    if (!g_have_last) return false;
    for (uint32_t i = 0; i < g_last.n_slots; ++i) {
        const snapshot::WardrobeSlot& s = g_last.slots[i];
        if (s.slot == slot && s.prop == is_prop) {
            if (styles_out) *styles_out = s.styles;
            return true;
        }
    }
    return false;
}

bool validate_piece(uint32_t ped, const Piece& p) {
    // IV has no IS_PED_COMPONENT_VARIATION_VALID equivalent; the
    // ped-handle argument is kept in the signature for parity with
    // the V plugin (and so a future per-piece live check has
    // somewhere to go). Silence the warning for now.
    (void)ped;
    uint32_t styles = 0;
    if (!slot_in_catalogue(p.slot, p.is_prop, &styles)) return false;
    if (p.is_prop) {
        if (p.drawable < 0) return true;   // clear
        if (static_cast<uint32_t>(p.drawable) >= styles) return false;
        // IV has no per-prop texture-count native; accept anything up
        // to a sane 0..255 (fits the wire format). The companion
        // handles "didn't stick" by reading back and reverting.
        return p.texture >= 0 && p.texture <= 0xFF;
    }
    // Components: use the catalogue counts the walker stored. IV has
    // no IS_PED_COMPONENT_VARIATION_VALID equivalent, so we validate
    // against our own copy of the counts; a mismatch is loud (the
    // game would just do nothing in that case, no crash).
    if (p.drawable < 0) return false;
    if (static_cast<uint32_t>(p.drawable) >= styles) return false;
    for (uint32_t i = 0; i < g_last.n_slots; ++i) {
        const snapshot::WardrobeSlot& s = g_last.slots[i];
        if (s.slot != p.slot || s.prop) continue;
        const uint16_t texs = s.colors[p.drawable];
        return p.texture >= 0 && static_cast<uint32_t>(p.texture) < texs;
    }
    return false;
}

void write_piece(uint32_t ped, const Piece& p) {
    if (p.is_prop) {
        if (p.drawable < 0) {
            game::clear_char_prop(ped, p.slot);
            return;
        }
        // Two-step write: index first (SET_CHAR_PROP_INDEX), then the
        // texture via SET_CHAR_PROP_INDEX_TEXTURE. The second native's
        // argument meaning is unclear in FusionFix; we pass (prop,
        // index, texture) per prediction and the mock asserts arity.
        game::set_char_prop_index(ped, p.slot,
                                  static_cast<uint32_t>(p.drawable));
        if (p.texture > 0) {
            game::set_char_prop_index_texture(ped, p.slot,
                static_cast<uint32_t>(p.drawable),
                static_cast<uint32_t>(p.texture));
        }
        return;
    }
    game::set_char_component_variation(ped, p.slot,
        static_cast<uint32_t>(p.drawable),
        static_cast<uint32_t>(p.texture));
}

const char* kErrNotOnFoot  = "blocked: in vehicle";
const char* kErrBadPiece   = "bad piece";
const char* kErrPedMissing = "no ped";

}  // namespace

namespace wardrobe {

void reset() {
    std::memset(&g_w, 0, sizeof(g_w));
    std::memset(&g_last, 0, sizeof(g_last));
    g_have_last = false;
}

void request() {
    g_w.requested = true;
}

void on_ped_changed() {
    if (g_w.in_progress || g_have_last) {
        thor_log("[wardrobe] ped changed, catalogue cleared");
    }
    g_w.in_progress = false;
    g_w.ped = 0;
    g_w.next_slot = 0;
    g_have_last = false;
    snapshot::clear_wardrobe();
}

void tick(uint32_t ped, int32_t episode) {
    if (ped == 0) {
        if (g_w.in_progress || g_have_last) on_ped_changed();
        return;
    }
    // Model change mid-walk: restart against the new ped.
    if (g_have_last) {
        const uint32_t model = game::get_char_model(ped);
        if (model != g_last.ped_model) {
            on_ped_changed();
            g_w.requested = true;
        }
    }
    if (!g_w.requested && !g_w.in_progress) return;
    if (!g_w.in_progress) start_walk(ped, episode);
    advance();
}

bool apply(const char* name, uint32_t arg0,
           const uint32_t* parts, uint32_t n_parts,
           uint32_t ped, bool in_vehicle, int32_t episode,
           char* err, size_t cap) {
    auto fail = [&](const char* msg) {
        std::snprintf(err, cap, "%s", msg);
        return false;
    };

    if (std::strcmp(name, "wardrobe_list") == 0) {
        if (ped == 0) return fail(kErrPedMissing);
        g_w.requested = true;
        if (g_have_last && g_last.ped_model == game::get_char_model(ped)) {
            snapshot::publish_wardrobe(g_last);
        }
        (void)episode;
        return true;
    }

    if (in_vehicle) return fail(kErrNotOnFoot);
    if (ped == 0)   return fail(kErrPedMissing);

    if (std::strcmp(name, "reset_outfit") == 0) {
        game::set_char_default_component_variation(ped);
        game::clear_all_char_props(ped);
        thor_log("[wardrobe] reset_outfit -> ok");
        refresh_all(ped);
        return true;
    }

    if (std::strcmp(name, "set_piece") == 0) {
        const Piece p = unpack_piece(arg0);
        if (!validate_piece(ped, p)) return fail(kErrBadPiece);
        write_piece(ped, p);
        thor_log("[wardrobe] set_piece %s=%u draw=%d tex=%d -> ok",
                 p.is_prop ? "prop" : "comp",
                 static_cast<unsigned>(p.slot), p.drawable, p.texture);
        refresh_all(ped);
        return true;
    }

    if (std::strcmp(name, "apply_outfit") == 0) {
        if (n_parts == 0 || parts == nullptr) return fail("empty outfit");
        for (uint32_t i = 0; i < n_parts; ++i) {
            const Piece p = unpack_piece(parts[i]);
            if (!validate_piece(ped, p)) {
                char msg[32];
                std::snprintf(msg, sizeof(msg), "bad part %u",
                              static_cast<unsigned>(i));
                return fail(msg);
            }
        }
        for (uint32_t i = 0; i < n_parts; ++i) {
            write_piece(ped, unpack_piece(parts[i]));
        }
        thor_log("[wardrobe] apply_outfit parts=%u -> ok",
                 static_cast<unsigned>(n_parts));
        refresh_all(ped);
        return true;
    }

    std::snprintf(err, cap, "unknown wardrobe command");
    return false;
}

}  // namespace wardrobe
