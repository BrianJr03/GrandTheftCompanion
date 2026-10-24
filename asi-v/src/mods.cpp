#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "cheats.h"      // reusing kErrNotInVehicle wording
#include "log.h"
#include "mods.h"
#include "natives.h"
#include "shv.h"
#include "snapshot.h"

namespace {

// modType ids walked per catalogue pass. 0..48 covers every slot the
// shop surfaces today; the walker publishes only ids with a non-zero
// GET_NUM_VEHICLE_MODS so a wrong guess here costs a header, not a
// crash. Toggle slots (18 / 20 / 22) are detected separately and go
// into the toggles[] list.
constexpr int32_t kFirstModType  = 0;
constexpr int32_t kLastModType   = 48;
constexpr int32_t kModTypeStock  = -1;
constexpr uint16_t kStockWire    = 0xFFFF;

// Fallback slot names used when GET_MOD_SLOT_NAME returns a key the
// game cannot resolve (DOES_TEXT_LABEL_EXIST == false). The app has
// its own copy as customs_slot_<n>; the plugin only sends what the
// game gives it, so we leave `name` empty here and let the app pick.

bool is_toggle_slot(int32_t slot) {
    return slot == 18 || slot == 20 || slot == 22;
}

// Session state. Everything in here is read and written on the
// script fiber only, so no locking is needed.
struct Walker {
    bool     in_progress;
    bool     requested;         // a client asked, not yet satisfied
    int32_t  veh;                // vehicle we are walking, 0 = none
    int32_t  next_slot;          // the next modType to read
    uint32_t kit;                // GET_VEHICLE_MOD_KIT at start
    snapshot::Mods out;          // accumulating result
};
Walker g_w{};

// The last catalogue we published; kept so a `mods_list` on an
// already-walked vehicle can republish without re-walking.
snapshot::Mods g_last{};
bool g_have_last = false;

void publish_now() {
    snapshot::publish_mods(g_w.out);
    std::memcpy(&g_last, &g_w.out, sizeof(g_last));
    g_have_last = true;
}

// Clear-and-set so a half-walked frame is never readable.
void start_walk(int32_t veh) {
    std::memset(&g_w.out, 0, sizeof(g_w.out));
    g_w.in_progress = true;
    g_w.veh = veh;
    g_w.next_slot = kFirstModType;
    g_w.out.vehicle = shv::call<uint32_t>(natives::GET_ENTITY_MODEL, veh);

    // SET_VEHICLE_MOD_KIT(veh, 0) is what every trainer calls to make
    // SET_VEHICLE_MOD / GET_NUM_VEHICLE_MODS answer. Harmless to
    // repeat; without it the whole catalogue is silently empty.
    shv::call<void>(natives::SET_VEHICLE_MOD_KIT, veh, 0);
    g_w.kit = shv::call<uint32_t>(natives::GET_VEHICLE_MOD_KIT, veh);
    g_w.out.kit = g_w.kit != 0xFFFF;   // 0xFFFF (-1 int) = no kit on this car

    g_w.out.wheel_type = shv::call<int32_t>(natives::GET_VEHICLE_WHEEL_TYPE, veh);
    g_w.out.tints      = shv::call<int32_t>(natives::GET_NUM_VEHICLE_WINDOW_TINTS);
    g_w.out.tint       = shv::call<int32_t>(natives::GET_VEHICLE_WINDOW_TINT, veh);
    g_w.out.bulletproof = !shv::call_bool(natives::GET_VEHICLE_TYRES_CAN_BURST, veh);
    // Any of the four neon sides on counts as "neon".
    g_w.out.neon = false;
    for (int side = 0; side < 4; ++side) {
        if (shv::call_bool(natives::GET_VEHICLE_NEON_ENABLED, veh, side)) {
            g_w.out.neon = true;
            break;
        }
    }
    const char* plate =
        shv::call<const char*>(natives::GET_VEHICLE_NUMBER_PLATE_TEXT, veh);
    if (plate) {
        std::snprintf(g_w.out.plate, sizeof(g_w.out.plate), "%s", plate);
    }
    // plate_style has no getter; the game's shop just overwrites it.
    // Leaving 0 is fine as a first value; writes keep it in sync.
    g_w.out.plate_style = 0;

    thor_log("[mods] walk start veh=%d model=0x%08X kit=%u",
             veh, g_w.out.vehicle, g_w.kit);
}

// Copy a game-owned C string into dst, tolerating nullptr / "NULL".
void copy_label(char* dst, size_t cap, const char* src) {
    if (!src || !src[0] || std::strcmp(src, "NULL") == 0) {
        if (cap) dst[0] = '\0';
        return;
    }
    size_t i = 0;
    for (; i + 1 < cap && src[i]; ++i) dst[i] = src[i];
    dst[i] = '\0';
}

// Resolve `key` to the localised label via GET_LABEL_TEXT; returns
// true + fills `out` when the label exists.
bool localise(int32_t veh, int32_t slot, int32_t idx, char* out, size_t cap) {
    const char* key = shv::call<const char*>(
        natives::GET_MOD_TEXT_LABEL, veh, slot, idx);
    if (!key || !key[0]) { out[0] = '\0'; return false; }
    const bool exists = shv::call_bool(natives::DOES_TEXT_LABEL_EXIST, key);
    if (!exists) { out[0] = '\0'; return false; }
    const char* text = shv::call<const char*>(natives::GET_LABEL_TEXT, key);
    copy_label(out, cap, text);
    return out[0] != '\0';
}

void read_slot(int32_t veh, int32_t slot) {
    const int32_t count =
        shv::call<int32_t>(natives::GET_NUM_VEHICLE_MODS, veh, slot);
    if (count <= 0) return;

    if (is_toggle_slot(slot)) {
        if (g_w.out.n_toggles >= snapshot::kMaxModToggles) return;
        snapshot::ModToggle& t = g_w.out.toggles[g_w.out.n_toggles++];
        std::memset(&t, 0, sizeof(t));
        t.slot = slot;
        t.on = shv::call_bool(natives::IS_TOGGLE_MOD_ON, veh, slot);
        // The game's own GXT key for a toggle slot ("CMOD_MOD_T...") —
        // the app has its own ("turbo", "tyre smoke", "xenon") so we
        // leave it empty if the lookup fails.
        const char* key =
            shv::call<const char*>(natives::GET_MOD_SLOT_NAME, veh, slot);
        if (key && key[0]
            && shv::call_bool(natives::DOES_TEXT_LABEL_EXIST, key)) {
            copy_label(t.name, sizeof(t.name),
                       shv::call<const char*>(natives::GET_LABEL_TEXT, key));
        }
        return;
    }

    if (g_w.out.n_slots >= snapshot::kMaxModSlots) {
        thor_log("[mods] WARN slot %d dropped: kMaxModSlots exceeded", slot);
        return;
    }
    snapshot::ModSlot& m = g_w.out.slots[g_w.out.n_slots++];
    std::memset(&m, 0, sizeof(m));
    m.slot = slot;
    m.cur  = shv::call<int32_t>(natives::GET_VEHICLE_MOD, veh, slot);

    // Slot name: the shop's own label. Empty when the key does not
    // resolve so the app can substitute its "customs_slot_N".
    const char* skey =
        shv::call<const char*>(natives::GET_MOD_SLOT_NAME, veh, slot);
    if (skey && skey[0]
        && shv::call_bool(natives::DOES_TEXT_LABEL_EXIST, skey)) {
        copy_label(m.name, sizeof(m.name),
                   shv::call<const char*>(natives::GET_LABEL_TEXT, skey));
    }

    uint32_t cap = static_cast<uint32_t>(count);
    if (cap > snapshot::kMaxModOptions) {
        thor_log("[mods] WARN slot %d has %u options; clamped to %u",
                 slot, cap, static_cast<uint32_t>(snapshot::kMaxModOptions));
        cap = snapshot::kMaxModOptions;
    }
    for (uint32_t i = 0; i < cap; ++i) {
        if (!localise(veh, slot, static_cast<int32_t>(i),
                      m.options[i], sizeof(m.options[i]))) {
            // Game has no name for this option — leave empty; the app
            // falls back to "option <N>".
            m.options[i][0] = '\0';
        }
    }
    m.n_options = cap;
}

// Walk one slice. True when the walk finished and the result was
// published.
bool advance() {
    if (!g_w.in_progress || g_w.veh == 0) return false;
    if (!shv::call_bool(natives::DOES_ENTITY_EXIST, g_w.veh)) {
        thor_log("[mods] walk aborted: vehicle %d no longer exists", g_w.veh);
        g_w.in_progress = false;
        return false;
    }
    const int32_t end = g_w.next_slot + static_cast<int32_t>(mods::kSlotsPerTick);
    for (int32_t s = g_w.next_slot; s < end && s <= kLastModType; ++s) {
        if (g_w.out.kit) read_slot(g_w.veh, s);
    }
    g_w.next_slot = end;
    if (g_w.next_slot > kLastModType) {
        thor_log("[mods] walk done veh=%d slots=%u toggles=%u",
                 g_w.veh, g_w.out.n_slots, g_w.out.n_toggles);
        g_w.in_progress = false;
        g_w.requested = false;
        publish_now();
        return true;
    }
    return false;
}

// Re-read just one slot into g_last (and republish) after a set_mod /
// toggle_mod / set_tint / set_plate / etc. — so the mods frame tracks
// the game's own state, not the tap.
void refresh_slot(int32_t veh, int32_t slot) {
    if (!g_have_last || !shv::call_bool(natives::DOES_ENTITY_EXIST, veh)) return;
    // Easiest: do the slot read against g_last directly.
    snapshot::Mods& m = g_last;
    if (is_toggle_slot(slot)) {
        for (uint32_t i = 0; i < m.n_toggles; ++i) {
            if (m.toggles[i].slot != slot) continue;
            m.toggles[i].on =
                shv::call_bool(natives::IS_TOGGLE_MOD_ON, veh, slot);
            break;
        }
    } else {
        for (uint32_t i = 0; i < m.n_slots; ++i) {
            if (m.slots[i].slot != slot) continue;
            m.slots[i].cur =
                shv::call<int32_t>(natives::GET_VEHICLE_MOD, veh, slot);
            break;
        }
    }
    snapshot::publish_mods(m);
}

// Rebuild the wheel-slot entries after SET_VEHICLE_WHEEL_TYPE because
// their option counts are per wheel type.
void rebuild_wheels(int32_t veh) {
    if (!g_have_last) return;
    snapshot::Mods& m = g_last;
    m.wheel_type = shv::call<int32_t>(natives::GET_VEHICLE_WHEEL_TYPE, veh);
    for (uint32_t i = 0; i < m.n_slots; ++i) {
        if (m.slots[i].slot != 23 && m.slots[i].slot != 24) continue;
        const int32_t slot = m.slots[i].slot;
        snapshot::ModSlot refreshed{};
        refreshed.slot = slot;
        refreshed.cur  = shv::call<int32_t>(natives::GET_VEHICLE_MOD, veh, slot);
        const int32_t count =
            shv::call<int32_t>(natives::GET_NUM_VEHICLE_MODS, veh, slot);
        uint32_t cap = count > 0 ? static_cast<uint32_t>(count) : 0;
        if (cap > snapshot::kMaxModOptions) cap = snapshot::kMaxModOptions;
        for (uint32_t j = 0; j < cap; ++j) {
            localise(veh, slot, static_cast<int32_t>(j),
                     refreshed.options[j], sizeof(refreshed.options[j]));
        }
        refreshed.n_options = cap;
        // Preserve the previously-resolved slot name.
        std::memcpy(refreshed.name, m.slots[i].name, sizeof(refreshed.name));
        std::memcpy(&m.slots[i], &refreshed, sizeof(refreshed));
    }
    snapshot::publish_mods(m);
}

// Pack / unpack helpers for the single-u32 wire slot.
inline uint32_t pack_mod(uint32_t slot, uint32_t idx) {
    return ((slot & 0xFFFF) << 16) | (idx & 0xFFFF);
}
inline void unpack_mod(uint32_t v, int32_t* slot, int32_t* idx) {
    *slot = static_cast<int32_t>((v >> 16) & 0xFFFF);
    const uint16_t raw = static_cast<uint16_t>(v & 0xFFFF);
    *idx = (raw == kStockWire) ? kModTypeStock : static_cast<int32_t>(raw);
}

// Shared slot lookup used by set_mod / toggle_mod / set_wheel_type.
bool slot_in_catalogue(int32_t slot, bool* is_toggle_out) {
    *is_toggle_out = false;
    if (!g_have_last) return false;
    const snapshot::Mods& m = g_last;
    if (is_toggle_slot(slot)) {
        for (uint32_t i = 0; i < m.n_toggles; ++i) {
            if (m.toggles[i].slot == slot) { *is_toggle_out = true; return true; }
        }
        return false;
    }
    for (uint32_t i = 0; i < m.n_slots; ++i) {
        if (m.slots[i].slot == slot) return true;
    }
    return false;
}

bool valid_plate_text(const char* t) {
    if (!t) return false;
    size_t n = std::strlen(t);
    if (n == 0 || n > 8) return false;
    for (size_t i = 0; i < n; ++i) {
        const char c = t[i];
        const bool ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ';
        if (!ok) return false;
    }
    return true;
}

const char* kErrNoKit        = "no mod kit";
const char* kErrSlotMissing  = "slot not available";
const char* kErrIndexRange   = "index out of range";
const char* kErrBadPlate     = "bad plate text";

}  // namespace

namespace mods {

void reset() {
    std::memset(&g_w, 0, sizeof(g_w));
    std::memset(&g_last, 0, sizeof(g_last));
    g_have_last = false;
}

void request() {
    g_w.requested = true;
}

void on_vehicle_changed() {
    if (g_w.in_progress || g_have_last) {
        thor_log("[mods] vehicle changed, catalogue cleared");
    }
    g_w.in_progress = false;
    g_w.veh = 0;
    g_w.next_slot = kFirstModType;
    g_have_last = false;
    snapshot::clear_mods();
}

void tick(int32_t veh) {
    if (veh == 0) {
        if (g_w.in_progress || g_have_last) on_vehicle_changed();
        return;
    }
    if (!g_w.requested && !g_w.in_progress) return;
    if (g_w.in_progress && g_w.veh != veh) {
        // Rare: the vehicle changed between the walk starting and
        // this tick. Restart against the new one.
        on_vehicle_changed();
        g_w.requested = true;
    }
    if (!g_w.in_progress) start_walk(veh);
    advance();
}

bool apply(const char* name, uint32_t arg, const char* text,
           int32_t veh, char* err, size_t cap) {
    auto fail = [&](const char* msg) {
        std::snprintf(err, cap, "%s", msg);
        return false;
    };

    if (std::strcmp(name, "mods_list") == 0) {
        if (veh == 0) return fail(cheats::kErrNotInVehicle);
        g_w.requested = true;
        if (g_have_last && g_w.out.vehicle ==
            shv::call<uint32_t>(natives::GET_ENTITY_MODEL, veh)) {
            // Catalogue already matches: bump the version so the
            // client fetches it again.
            snapshot::publish_mods(g_last);
        }
        return true;
    }

    if (veh == 0) return fail(cheats::kErrNotInVehicle);
    if (!g_have_last || !g_last.kit) {
        // The walker runs on the fiber; if the client sent a command
        // before the first walk completed, g_last may not be set yet.
        // Fall through to no-kit when the kit genuinely is missing.
        if (g_have_last && !g_last.kit) return fail(kErrNoKit);
    }

    if (std::strcmp(name, "set_mod") == 0) {
        int32_t slot = 0, idx = 0;
        unpack_mod(arg, &slot, &idx);
        bool tog = false;
        if (!slot_in_catalogue(slot, &tog) || tog) return fail(kErrSlotMissing);
        if (idx != kModTypeStock) {
            const int32_t count =
                shv::call<int32_t>(natives::GET_NUM_VEHICLE_MODS, veh, slot);
            if (idx < 0 || idx >= count) return fail(kErrIndexRange);
        }
        if (idx == kModTypeStock) {
            shv::call<void>(natives::REMOVE_VEHICLE_MOD, veh, slot);
        } else {
            // customTires = 1 so the plated wheels / lowrider variants
            // actually render differently; cheap for other slots.
            shv::call<void>(natives::SET_VEHICLE_MOD, veh, slot, idx, 1);
        }
        thor_log("[mods] set_mod slot=%d index=%d -> ok", slot, idx);
        refresh_slot(veh, slot);
        return true;
    }
    if (std::strcmp(name, "toggle_mod") == 0) {
        const int32_t slot = static_cast<int32_t>(arg);
        bool tog = false;
        if (!slot_in_catalogue(slot, &tog) || !tog) return fail(kErrSlotMissing);
        const bool now = shv::call_bool(natives::IS_TOGGLE_MOD_ON, veh, slot);
        shv::call<void>(natives::TOGGLE_VEHICLE_MOD, veh, slot, !now);
        thor_log("[mods] toggle_mod slot=%d -> %s", slot, now ? "off" : "on");
        refresh_slot(veh, slot);
        return true;
    }
    if (std::strcmp(name, "set_wheel_type") == 0) {
        const int32_t t = static_cast<int32_t>(arg);
        if (t < 0 || t > 10) return fail(kErrIndexRange);
        shv::call<void>(natives::SET_VEHICLE_WHEEL_TYPE, veh, t);
        thor_log("[mods] set_wheel_type %d -> ok", t);
        rebuild_wheels(veh);
        return true;
    }
    if (std::strcmp(name, "set_tint") == 0) {
        const int32_t t = static_cast<int32_t>(arg);
        const int32_t n = shv::call<int32_t>(natives::GET_NUM_VEHICLE_WINDOW_TINTS);
        if (t < 0 || t >= n) return fail(kErrIndexRange);
        shv::call<void>(natives::SET_VEHICLE_WINDOW_TINT, veh, t);
        thor_log("[mods] set_tint %d -> ok", t);
        if (g_have_last) { g_last.tint = t; snapshot::publish_mods(g_last); }
        return true;
    }
    if (std::strcmp(name, "set_plate_style") == 0) {
        const int32_t s = static_cast<int32_t>(arg);
        // Six base-game styles. Writing past 5 is harmless (the game
        // ignores it); refuse here so a wrong tap is loud.
        if (s < 0 || s > 5) return fail(kErrIndexRange);
        shv::call<void>(natives::SET_VEHICLE_NUMBER_PLATE_TEXT_INDEX, veh, s);
        thor_log("[mods] set_plate_style %d -> ok", s);
        if (g_have_last) { g_last.plate_style = s; snapshot::publish_mods(g_last); }
        return true;
    }
    if (std::strcmp(name, "set_plate") == 0) {
        if (!valid_plate_text(text)) return fail(kErrBadPlate);
        shv::call<void>(natives::SET_VEHICLE_NUMBER_PLATE_TEXT, veh, text);
        thor_log("[mods] set_plate '%s' -> ok", text);
        if (g_have_last) {
            std::snprintf(g_last.plate, sizeof(g_last.plate), "%s", text);
            snapshot::publish_mods(g_last);
        }
        return true;
    }
    if (std::strcmp(name, "set_neon") == 0) {
        const uint32_t rgb = arg & 0x00FFFFFFu;
        const bool on = rgb != 0;
        const int r = static_cast<int>((rgb >> 16) & 0xFF);
        const int g = static_cast<int>((rgb >> 8) & 0xFF);
        const int b = static_cast<int>(rgb & 0xFF);
        for (int side = 0; side < 4; ++side) {
            shv::call<void>(natives::SET_VEHICLE_NEON_ENABLED, veh, side, on);
        }
        if (on) {
            shv::call<void>(natives::SET_VEHICLE_NEON_COLOUR, veh, r, g, b);
        }
        thor_log("[mods] set_neon #%06X -> ok", static_cast<unsigned>(rgb));
        if (g_have_last) { g_last.neon = on; snapshot::publish_mods(g_last); }
        return true;
    }
    if (std::strcmp(name, "set_smoke") == 0) {
        const uint32_t rgb = arg & 0x00FFFFFFu;
        const int r = static_cast<int>((rgb >> 16) & 0xFF);
        const int g = static_cast<int>((rgb >> 8) & 0xFF);
        const int b = static_cast<int>(rgb & 0xFF);
        shv::call<void>(natives::SET_VEHICLE_TYRE_SMOKE_COLOR, veh, r, g, b);
        thor_log("[mods] set_smoke #%06X -> ok", static_cast<unsigned>(rgb));
        return true;
    }
    if (std::strcmp(name, "set_xenon_colour") == 0) {
        // 0xFFFFFFFF (-1 int) means the default stock blue. Values
        // past the game's cap (12 at time of writing) are clamped by
        // the game itself.
        int32_t idx = static_cast<int32_t>(arg);
        if (arg == 0xFFFFFFFFu) idx = -1;
        shv::call<void>(natives::SET_VEHICLE_XENON_LIGHT_COLOR_INDEX, veh, idx);
        thor_log("[mods] set_xenon_colour %d -> ok", idx);
        return true;
    }
    if (std::strcmp(name, "set_bulletproof_tyres") == 0) {
        const bool bp = arg != 0;
        // The game's native is "can burst"; invert to match the UI.
        shv::call<void>(natives::SET_VEHICLE_TYRES_CAN_BURST, veh, !bp);
        thor_log("[mods] set_bulletproof_tyres %d -> ok", bp ? 1 : 0);
        if (g_have_last) { g_last.bulletproof = bp; snapshot::publish_mods(g_last); }
        return true;
    }

    std::snprintf(err, cap, "unknown mod command");
    return false;
}

}  // namespace mods
