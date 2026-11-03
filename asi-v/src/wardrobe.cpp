#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "cheats.h"      // kErrNotInVehicle wording reused
#include "joaat.h"
#include "log.h"
#include "natives.h"
#include "shv.h"
#include "snapshot.h"
#include "wardrobe.h"

namespace {

// Component slots the walker is willing to publish, in walk order.
// 0 (head) and 10 (decals) are deliberately hidden — face edits are
// not a wardrobe action and would need a separate character creator.
// The walker also skips any slot that reports zero drawables for the
// current ped, so Director Mode / mods / animal peds that use a
// different slot set self-filter.
constexpr uint8_t kComponentWalkOrder[] = {
    1, 2, 3, 4, 5, 6, 7, 8, 9, 11,
};
constexpr size_t kComponentWalkCount =
    sizeof(kComponentWalkOrder) / sizeof(kComponentWalkOrder[0]);

// Prop slots the walker is willing to publish, in walk order.
// Matches the trainer convention: hat (0), glasses (1), ears (2),
// watch (6), bracelet (7). 3..5 are never populated on SP peds.
constexpr uint8_t kPropWalkOrder[] = { 0, 1, 2, 6, 7 };
constexpr size_t kPropWalkCount =
    sizeof(kPropWalkOrder) / sizeof(kPropWalkOrder[0]);

// Total walk length: components + props. The walker drives a single
// index 0..kWalkTotal-1 and resolves it to (prop, slot_id) via these
// arrays.
constexpr uint32_t kWalkTotal =
    static_cast<uint32_t>(kComponentWalkCount + kPropWalkCount);

// Wire packing. See bridge.h "Wardrobe" for the field layout.
constexpr uint32_t kBitProp     = 1u << 24;
constexpr uint32_t kSlotMask    = 0xFu;
constexpr uint32_t kSlotShift   = 20u;
constexpr uint32_t kDrawMask    = 0xFFFu;
constexpr uint32_t kDrawShift   = 8u;
constexpr uint32_t kTexMask     = 0xFFu;

// snapshot::Character for a ped model. Mirrors script.cpp's
// character_of so the wardrobe frame reports the same character the
// HUD does without a cross-TU call. Director-mode or animal peds
// land in kCharOther and get the generic "style N" labels on the UI.
constexpr uint32_t kModelMichael  = joaat("player_zero");
constexpr uint32_t kModelFranklin = joaat("player_one");
constexpr uint32_t kModelTrevor   = joaat("player_two");

int8_t character_for_model(uint32_t model) {
    if (model == kModelMichael)  return snapshot::kCharMichael;
    if (model == kModelFranklin) return snapshot::kCharFranklin;
    if (model == kModelTrevor)   return snapshot::kCharTrevor;
    return snapshot::kCharOther;
}

// Decoded piece for apply().
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

// Session state. Everything in here is read and written on the
// script fiber only, so no locking is needed.
struct Walker {
    bool              in_progress;
    bool              requested;    // a client asked, not yet satisfied
    int32_t           ped;           // ped we are walking, 0 = none
    uint32_t          ped_model;     // model at walk start
    int8_t            character;     // snapshot::Character at walk start
    uint32_t          next_slot;     // next kWalkTotal index to read
    snapshot::Wardrobe out;          // accumulating result
};
Walker g_w{};

// Last catalogue we published; kept so a wardrobe_list on an
// already-walked ped can republish without re-walking.
snapshot::Wardrobe g_last{};
bool g_have_last = false;

// First-session cheat opt-in: route the first wardrobe write through
// STAT_SET_CHEAT_IS_ACTIVE the same way cheats::run does. The game's
// save stat survives for the rest of the session so repeated writes
// are harmless; we track it here to keep the call out of the hot path.
bool g_cheat_marked = false;

void mark_cheat_once() {
    if (g_cheat_marked) return;
    shv::call<void>(natives::STAT_SET_CHEAT_IS_ACTIVE);
    g_cheat_marked = true;
}

void publish_now() {
    snapshot::publish_wardrobe(g_w.out);
    std::memcpy(&g_last, &g_w.out, sizeof(g_last));
    g_have_last = true;
}

// Clear-and-set so a half-walked frame is never readable.
void start_walk(int32_t ped) {
    std::memset(&g_w.out, 0, sizeof(g_w.out));
    g_w.in_progress = true;
    g_w.ped = ped;
    g_w.next_slot = 0;
    g_w.ped_model = shv::call<uint32_t>(natives::GET_ENTITY_MODEL, ped);
    g_w.out.ped_model = g_w.ped_model;
    g_w.out.character = character_for_model(g_w.ped_model);
    g_w.character = g_w.out.character;

    thor_log("[wardrobe] walk start ped=%d model=0x%08X character=%d",
             ped, g_w.ped_model, static_cast<int>(g_w.out.character));
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

// Read one component slot into g_w.out, if it has at least one
// drawable this ped. Clamp the drawable count at kMaxWardrobeStyles.
void read_component_slot(int32_t ped, uint8_t slot) {
    const int32_t drawables =
        shv::call<int32_t>(natives::GET_NUMBER_OF_PED_DRAWABLE_VARIATIONS,
                           ped, static_cast<int32_t>(slot));
    if (drawables <= 0) return;
    if (g_w.out.n_slots >= snapshot::kMaxWardrobeSlots) {
        thor_log("[wardrobe] WARN component slot %u dropped: kMaxWardrobeSlots",
                 static_cast<unsigned>(slot));
        return;
    }
    snapshot::WardrobeSlot& out = g_w.out.slots[g_w.out.n_slots++];
    std::memset(&out, 0, sizeof(out));
    out.slot = slot;
    out.prop = false;
    out.cur = shv::call<int32_t>(natives::GET_PED_DRAWABLE_VARIATION,
                                 ped, static_cast<int32_t>(slot));
    out.tex = shv::call<int32_t>(natives::GET_PED_TEXTURE_VARIATION,
                                 ped, static_cast<int32_t>(slot));
    out.pal = shv::call<int32_t>(natives::GET_PED_PALETTE_VARIATION,
                                 ped, static_cast<int32_t>(slot));
    uint32_t cap = static_cast<uint32_t>(drawables);
    if (cap > snapshot::kMaxWardrobeStyles) {
        thor_log("[wardrobe] WARN component %u has %u styles; clamped to %u",
                 static_cast<unsigned>(slot), cap,
                 static_cast<unsigned>(snapshot::kMaxWardrobeStyles));
        cap = snapshot::kMaxWardrobeStyles;
    }
    out.styles = cap;
    for (uint32_t d = 0; d < cap; ++d) {
        const int32_t tex =
            shv::call<int32_t>(natives::GET_NUMBER_OF_PED_TEXTURE_VARIATIONS,
                               ped, static_cast<int32_t>(slot),
                               static_cast<int32_t>(d));
        out.colors[d] = static_cast<uint16_t>(tex < 0 ? 0 : tex);
    }
}

// Read one prop slot. GTA V DOES expose a texture count per drawable,
// so we fill colors[] like a component slot. Empty prop ("none")
// shows up as cur = -1 (GET_PED_PROP_INDEX returns -1 on an unfitted
// slot).
void read_prop_slot(int32_t ped, uint8_t slot) {
    const int32_t drawables =
        shv::call<int32_t>(natives::GET_NUMBER_OF_PED_PROP_DRAWABLE_VARIATIONS,
                           ped, static_cast<int32_t>(slot));
    if (drawables <= 0) return;
    if (g_w.out.n_slots >= snapshot::kMaxWardrobeSlots) {
        thor_log("[wardrobe] WARN prop slot %u dropped: kMaxWardrobeSlots",
                 static_cast<unsigned>(slot));
        return;
    }
    snapshot::WardrobeSlot& out = g_w.out.slots[g_w.out.n_slots++];
    std::memset(&out, 0, sizeof(out));
    out.slot = slot;
    out.prop = true;
    out.cur = shv::call<int32_t>(natives::GET_PED_PROP_INDEX,
                                 ped, static_cast<int32_t>(slot), 0);
    out.tex = shv::call<int32_t>(natives::GET_PED_PROP_TEXTURE_INDEX,
                                 ped, static_cast<int32_t>(slot));
    out.pal = 0;
    uint32_t cap = static_cast<uint32_t>(drawables);
    if (cap > snapshot::kMaxWardrobeStyles) cap = snapshot::kMaxWardrobeStyles;
    out.styles = cap;
    for (uint32_t d = 0; d < cap; ++d) {
        const int32_t tex =
            shv::call<int32_t>(natives::GET_NUMBER_OF_PED_PROP_TEXTURE_VARIATIONS,
                               ped, static_cast<int32_t>(slot),
                               static_cast<int32_t>(d));
        out.colors[d] = static_cast<uint16_t>(tex < 0 ? 0 : tex);
    }
}

bool advance() {
    if (!g_w.in_progress || g_w.ped == 0) return false;
    if (!shv::call_bool(natives::DOES_ENTITY_EXIST, g_w.ped)) {
        thor_log("[wardrobe] walk aborted: ped %d no longer exists", g_w.ped);
        g_w.in_progress = false;
        return false;
    }
    const uint32_t end = g_w.next_slot + wardrobe::kSlotsPerTick;
    for (uint32_t i = g_w.next_slot; i < end && i < kWalkTotal; ++i) {
        bool is_prop = false;
        const uint8_t slot = slot_for_walk_index(i, &is_prop);
        if (is_prop) read_prop_slot(g_w.ped, slot);
        else         read_component_slot(g_w.ped, slot);
    }
    g_w.next_slot = end;
    if (g_w.next_slot >= kWalkTotal) {
        thor_log("[wardrobe] walk done ped=%d slots=%u",
                 g_w.ped, g_w.out.n_slots);
        g_w.in_progress = false;
        g_w.requested = false;
        publish_now();
        return true;
    }
    return false;
}

// Re-read every slot into g_last after a write so the frame tracks
// the ped's actual state, not the tap. The slot set and order never
// change for a given ped, so we overwrite each row's live fields in
// place; counts (styles / colours) do not need re-reading.
void refresh_all(int32_t ped) {
    if (!g_have_last) return;
    if (!shv::call_bool(natives::DOES_ENTITY_EXIST, ped)) return;
    snapshot::Wardrobe& m = g_last;
    for (uint32_t i = 0; i < m.n_slots; ++i) {
        snapshot::WardrobeSlot& s = m.slots[i];
        if (s.prop) {
            s.cur = shv::call<int32_t>(natives::GET_PED_PROP_INDEX,
                                       ped, static_cast<int32_t>(s.slot), 0);
            s.tex = shv::call<int32_t>(natives::GET_PED_PROP_TEXTURE_INDEX,
                                       ped, static_cast<int32_t>(s.slot));
        } else {
            s.cur = shv::call<int32_t>(natives::GET_PED_DRAWABLE_VARIATION,
                                       ped, static_cast<int32_t>(s.slot));
            s.tex = shv::call<int32_t>(natives::GET_PED_TEXTURE_VARIATION,
                                       ped, static_cast<int32_t>(s.slot));
            s.pal = shv::call<int32_t>(natives::GET_PED_PALETTE_VARIATION,
                                       ped, static_cast<int32_t>(s.slot));
        }
    }
    snapshot::publish_wardrobe(m);
}

bool slot_in_catalogue(uint8_t slot, bool is_prop, uint32_t* styles_out,
                       uint32_t* idx_out) {
    if (!g_have_last) return false;
    for (uint32_t i = 0; i < g_last.n_slots; ++i) {
        const snapshot::WardrobeSlot& s = g_last.slots[i];
        if (s.slot == slot && s.prop == is_prop) {
            if (styles_out) *styles_out = s.styles;
            if (idx_out)    *idx_out = i;
            return true;
        }
    }
    return false;
}

// Returns true if this piece is applyable on the ped right now:
// slot exists in the catalogue, drawable is in range, texture is in
// range, components additionally pass IS_PED_COMPONENT_VARIATION_VALID.
// A prop with drawable < 0 is a clear request (always valid when the
// prop slot exists).
bool validate_piece(int32_t ped, const Piece& p) {
    uint32_t idx = 0;
    uint32_t styles = 0;
    if (!slot_in_catalogue(p.slot, p.is_prop, &styles, &idx)) return false;
    if (p.is_prop) {
        if (p.drawable < 0) return true;   // clear
        if (static_cast<uint32_t>(p.drawable) >= styles) return false;
        const uint16_t texs = g_last.slots[idx].colors[p.drawable];
        return p.texture >= 0 && static_cast<uint16_t>(p.texture) < texs;
    }
    if (p.drawable < 0) return false;
    if (static_cast<uint32_t>(p.drawable) >= styles) return false;
    return shv::call_bool(natives::IS_PED_COMPONENT_VARIATION_VALID,
                          ped, static_cast<int32_t>(p.slot),
                          p.drawable, p.texture);
}

// Write one validated piece. Components keep the ped's existing
// palette for this slot (never hard-coded) because clothing palette
// numbers are not stable across models. Props with drawable < 0 call
// CLEAR_PED_PROP.
void write_piece(int32_t ped, const Piece& p) {
    mark_cheat_once();
    if (p.is_prop) {
        if (p.drawable < 0) {
            shv::call<void>(natives::CLEAR_PED_PROP,
                            ped, static_cast<int32_t>(p.slot), 0);
            return;
        }
        shv::call<void>(natives::SET_PED_PROP_INDEX,
                        ped, static_cast<int32_t>(p.slot),
                        p.drawable, p.texture, 1, 0);
        return;
    }
    const int32_t pal = shv::call<int32_t>(natives::GET_PED_PALETTE_VARIATION,
                                           ped, static_cast<int32_t>(p.slot));
    shv::call<void>(natives::SET_PED_COMPONENT_VARIATION,
                    ped, static_cast<int32_t>(p.slot),
                    p.drawable, p.texture, pal);
}

const char* kErrNotOnFoot    = "blocked: in vehicle";
const char* kErrBadPiece     = "bad piece";
const char* kErrPedMissing   = "no ped";

}  // namespace

namespace wardrobe {

void reset() {
    std::memset(&g_w, 0, sizeof(g_w));
    std::memset(&g_last, 0, sizeof(g_last));
    g_have_last = false;
    g_cheat_marked = false;
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

void tick(int32_t ped) {
    if (ped == 0) {
        if (g_w.in_progress || g_have_last) on_ped_changed();
        return;
    }
    // Model change mid-walk: restart against the new ped. The script
    // layer calls on_ped_changed() already on a character switch so
    // this is a belt-and-braces guard.
    if (g_have_last) {
        const uint32_t model = shv::call<uint32_t>(natives::GET_ENTITY_MODEL, ped);
        if (model != g_last.ped_model) {
            on_ped_changed();
            g_w.requested = true;
        }
    }
    if (!g_w.requested && !g_w.in_progress) return;
    if (!g_w.in_progress) start_walk(ped);
    advance();
}

bool apply(const char* name, uint32_t arg0,
           const uint32_t* parts, uint32_t n_parts,
           int32_t ped, bool in_vehicle, char* err, size_t cap) {
    auto fail = [&](const char* msg) {
        std::snprintf(err, cap, "%s", msg);
        return false;
    };

    // wardrobe_list is the one command that works even without a
    // catalogue yet — asking for one is how the catalogue appears.
    if (std::strcmp(name, "wardrobe_list") == 0) {
        if (ped == 0) return fail(kErrPedMissing);
        g_w.requested = true;
        if (g_have_last && g_last.ped_model ==
            shv::call<uint32_t>(natives::GET_ENTITY_MODEL, ped)) {
            // Catalogue already matches: bump the version so the
            // client fetches it again.
            snapshot::publish_wardrobe(g_last);
        }
        return true;
    }

    if (in_vehicle) return fail(kErrNotOnFoot);
    if (ped == 0)   return fail(kErrPedMissing);

    if (std::strcmp(name, "reset_outfit") == 0) {
        mark_cheat_once();
        shv::call<void>(natives::SET_PED_DEFAULT_COMPONENT_VARIATION, ped);
        shv::call<void>(natives::CLEAR_ALL_PED_PROPS, ped, 0);
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
        // Validate everything first so a single bad part cannot leave
        // the ped half-dressed.
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
