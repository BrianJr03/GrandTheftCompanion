#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>

#include "armory.h"
#include "components.h"
#include "log.h"
#include "natives.h"
#include "shv.h"
#include "snapshot.h"
#include "weapons.h"

namespace {

// Weapon struct layout confirmed by the Gate A0 hex dumps on build
// 1.0.3751.0: 7 ints as 8-byte slots then 4 x 64-byte label buffers.
// We only need the weapon hash (slot 1) and the name label (offset
// 0x38); clip size and tint count come from dedicated natives.
constexpr size_t kWeaponBufBytes      = 312;
constexpr size_t kWeaponOffHash       = 0x08;
constexpr size_t kWeaponOffNameLabel  = 0x38;

// Component struct: 6 int slots then 2 x 64-byte labels.
constexpr size_t kComponentBufBytes   = 176;
constexpr size_t kCompOffBone         = 0x00;
constexpr size_t kCompOffActive       = 0x08;
constexpr size_t kCompOffHash         = 0x18;
constexpr size_t kCompOffNameLabel    = 0x30;

// Soft cap on the session map. The real game reports 71 today; even
// a hundred-weapon DLC would fit, and anything beyond 256 is almost
// certainly a wrong-build mismatch rather than a legitimate list.
constexpr uint32_t kMaxWeaponIndices  = 256;

struct IndexEntry {
    uint32_t hash;    // joaat weapon hash
    int32_t  widx;    // DLC index
};

// Session state: everything in here is read / written on the script
// fiber only, so no locking is needed.
struct Session {
    bool        built;              // scan complete
    bool        in_progress;        // scan slice walking
    int32_t     total;              // GET_NUM_DLC_WEAPONS at scan start
    int32_t     next_idx;           // next slot to read
    uint32_t    n_entries;
    IndexEntry  entries[kMaxWeaponIndices];
};
Session g_s{};

struct PendingRequest {
    bool     pending;
    uint32_t hash;
};
PendingRequest g_req{};

// The weapon the client is currently looking at. Set by request()
// after a successful publish; set_* commands verify their widx
// matches this weapon's widx so a stale frame can only miss, never
// land on a different weapon.
int32_t  g_current_widx = -1;
uint32_t g_current_hash = 0;

// Fallback cache: the per-cidx component hashes the plugin published
// most recently for a non-catalogue weapon. set_component for widx=-1
// uses this to resolve cidx to a hash — GET_DLC_WEAPON_COMPONENT_DATA
// has no entry for a weapon the DLC catalogue does not list.
struct FallbackCache {
    uint32_t weapon_hash;
    uint32_t count;
    uint32_t hashes[snapshot::kMaxArmoryComponents];
    uint32_t bones[snapshot::kMaxArmoryComponents];
    bool     is_default[snapshot::kMaxArmoryComponents];
};
FallbackCache g_fallback{};

uint32_t read_u32(const uint8_t* buf, size_t off) {
    uint32_t v = 0;
    std::memcpy(&v, buf + off, sizeof(v));
    return v;
}

// Read one weapon struct into `buf`. Returns false when the native
// says the entry is unusable; the caller skips it.
bool read_weapon(int32_t idx, uint8_t* buf) {
    std::memset(buf, 0, kWeaponBufBytes);
    return shv::call_bool(natives::GET_DLC_WEAPON_DATA, idx, buf);
}

bool read_component(int32_t widx, int32_t cidx, uint8_t* buf) {
    std::memset(buf, 0, kComponentBufBytes);
    return shv::call_bool(natives::GET_DLC_WEAPON_COMPONENT_DATA,
                          widx, cidx, buf);
}

// Game-owned C string via GET_LABEL_TEXT. "" when the key is empty,
// absent, or resolves to the game's "NULL" sentinel.
void label_for(const char* key, char* out, size_t cap) {
    if (cap == 0) return;
    out[0] = '\0';
    if (!key || !key[0]) return;
    const char* text = shv::call<const char*>(natives::GET_LABEL_TEXT, key);
    if (!text || !text[0] || std::strcmp(text, "NULL") == 0) return;
    size_t i = 0;
    for (; i + 1 < cap && text[i]; ++i) out[i] = text[i];
    out[i] = '\0';
}

// Session-build walker. One slice per tick.
void advance_session() {
    if (g_s.built) return;
    if (!g_s.in_progress) {
        g_s.total = shv::call<int32_t>(natives::GET_NUM_DLC_WEAPONS);
        if (g_s.total < 0) g_s.total = 0;
        if (g_s.total > static_cast<int32_t>(kMaxWeaponIndices)) {
            thor_log("[armory] WARN GET_NUM_DLC_WEAPONS=%d clamped to %u",
                     g_s.total, static_cast<unsigned>(kMaxWeaponIndices));
            g_s.total = static_cast<int32_t>(kMaxWeaponIndices);
        }
        g_s.next_idx = 0;
        g_s.n_entries = 0;
        g_s.in_progress = true;
        thor_log("[armory] session build start: %d DLC weapons", g_s.total);
    }
    const int32_t end = g_s.next_idx +
        static_cast<int32_t>(armory::kSlotsPerTick);
    uint8_t buf[kWeaponBufBytes];
    for (int32_t i = g_s.next_idx; i < end && i < g_s.total; ++i) {
        if (!read_weapon(i, buf)) continue;
        if (g_s.n_entries >= kMaxWeaponIndices) continue;
        IndexEntry& e = g_s.entries[g_s.n_entries++];
        e.hash = read_u32(buf, kWeaponOffHash);
        e.widx = i;
    }
    g_s.next_idx = end;
    if (g_s.next_idx >= g_s.total) {
        g_s.in_progress = false;
        g_s.built = true;
        thor_log("[armory] session build done: %u catalogue entries",
                 g_s.n_entries);
    }
}

int32_t widx_for(uint32_t hash) {
    for (uint32_t i = 0; i < g_s.n_entries; ++i) {
        if (g_s.entries[i].hash == hash) return g_s.entries[i].widx;
    }
    return -1;
}

// Build and publish an Armory frame for `hash`. Returns true if a
// frame was published; false means the client should be told the
// weapon is not owned.
bool publish_for(uint32_t hash, int32_t ped) {
    if (hash == 0) return false;
    if (!shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, hash, 0)) {
        return false;
    }

    snapshot::Armory out{};
    out.weapon = hash;
    out.widx   = widx_for(hash);

    // Weapon name: pull from catalogue when we have it, otherwise fall
    // back to the owned-weapon table so the strip still has a label.
    if (out.widx >= 0) {
        uint8_t wbuf[kWeaponBufBytes];
        if (read_weapon(out.widx, wbuf)) {
            const char* key =
                reinterpret_cast<const char*>(wbuf + kWeaponOffNameLabel);
            label_for(key, out.name, sizeof(out.name));
        }
    }
    if (!out.name[0]) {
        const char* fallback = weapons::name_of(hash);
        if (fallback && fallback[0]) {
            std::snprintf(out.name, sizeof(out.name), "%s", fallback);
        }
    }

    // Tint / ammo / clip. These natives accept any weapon hash the
    // ped owns, including base-vanilla weapons outside the catalogue.
    out.tints    = shv::call<int32_t>(natives::GET_WEAPON_TINT_COUNT, hash);
    out.tint     = shv::call<int32_t>(natives::GET_PED_WEAPON_TINT_INDEX,
                                      ped, hash);
    out.ammo     = shv::call<int32_t>(natives::GET_AMMO_IN_PED_WEAPON,
                                      ped, hash);
    alignas(8) int64_t max_slot = 0;
    if (shv::call_bool(natives::GET_MAX_AMMO, ped, hash, &max_slot)) {
        out.max_ammo = static_cast<int32_t>(max_slot);
    }
    // GET_WEAPON_CLIP_SIZE is weapon-archetype only — it does not
    // follow a fitted extended clip. GET_MAX_AMMO_IN_CLIP reads the
    // current clip for this ped's fit, which is what the sheet needs.
    out.clip     = shv::call<int32_t>(natives::GET_MAX_AMMO_IN_CLIP,
                                      ped, hash, 1);

    // Components. Two sources:
    //   out.widx >= 0: walk GET_DLC_WEAPON_COMPONENT_DATA — the game's
    //     own shop list, labels resolve via GET_LABEL_TEXT.
    //   out.widx == -1: fall back to components.h for base-game weapons
    //     the DLC catalogue intentionally omits (vanilla Pistol, Micro
    //     SMG, Carbine Rifle, Pump Shotgun, etc.). Each candidate is
    //     filtered by DOES_WEAPON_TAKE_WEAPON_COMPONENT, so a stale
    //     entry in a future game build just drops out.
    g_fallback = FallbackCache{};
    if (out.widx >= 0) {
        const int32_t n_comp = shv::call<int32_t>(
            natives::GET_NUM_DLC_WEAPON_COMPONENTS, out.widx);
        const uint32_t cap = (n_comp < 0) ? 0u : static_cast<uint32_t>(n_comp);
        uint8_t cbuf[kComponentBufBytes];
        for (uint32_t c = 0; c < cap; ++c) {
            if (out.n_options >= snapshot::kMaxArmoryComponents) {
                thor_log("[armory] WARN weapon 0x%08X: component %u dropped "
                         "(kMaxArmoryComponents exceeded)", hash, c);
                break;
            }
            if (!read_component(out.widx, static_cast<int32_t>(c), cbuf)) {
                continue;
            }
            snapshot::ArmoryOption& op = out.options[out.n_options++];
            op.cidx       = static_cast<int32_t>(c);
            op.bone       = read_u32(cbuf, kCompOffBone);
            op.is_default = read_u32(cbuf, kCompOffActive) != 0;
            const uint32_t chash = read_u32(cbuf, kCompOffHash);
            op.is_fitted  = shv::call_bool(
                natives::HAS_PED_GOT_WEAPON_COMPONENT, ped, hash, chash);
            const char* key =
                reinterpret_cast<const char*>(cbuf + kCompOffNameLabel);
            label_for(key, op.name, sizeof(op.name));
        }
    } else if (const components::WeaponTable* fb = components::lookup(hash)) {
        g_fallback.weapon_hash = hash;
        for (size_t i = 0; i < fb->count; ++i) {
            const components::Entry& e = fb->entries[i];
            if (!shv::call_bool(natives::DOES_WEAPON_TAKE_WEAPON_COMPONENT,
                                hash, e.hash)) continue;
            if (out.n_options >= snapshot::kMaxArmoryComponents) {
                thor_log("[armory] WARN weapon 0x%08X: fallback component %u "
                         "dropped (kMaxArmoryComponents exceeded)",
                         hash, static_cast<unsigned>(i));
                break;
            }
            const uint32_t idx = out.n_options;
            snapshot::ArmoryOption& op = out.options[idx];
            op.cidx       = static_cast<int32_t>(idx);
            op.bone       = (e.category < 6)
                            ? components::kBoneForCategory[e.category] : 0;
            op.is_default = e.is_default;
            op.is_fitted  = shv::call_bool(
                natives::HAS_PED_GOT_WEAPON_COMPONENT, ped, hash, e.hash);
            std::snprintf(op.name, sizeof(op.name), "%s", e.label);
            g_fallback.hashes[idx]     = e.hash;
            g_fallback.bones[idx]      = op.bone;
            g_fallback.is_default[idx] = e.is_default;
            out.n_options = idx + 1;
            g_fallback.count = idx + 1;
        }
    }

    snapshot::publish_armory(out);
    g_current_hash = hash;
    g_current_widx = out.widx;
    thor_log("[armory] published weapon=0x%08X widx=%d name='%s' "
             "options=%u tint=%d/%d ammo=%d/%d clip=%d",
             hash, out.widx, out.name, out.n_options, out.tint, out.tints,
             out.ammo, out.max_ammo, out.clip);
    return true;
}

// Component hash for (widx, cidx). Zero if the read fails.
uint32_t component_hash_for(int32_t widx, int32_t cidx) {
    uint8_t cbuf[kComponentBufBytes];
    if (!read_component(widx, cidx, cbuf)) return 0;
    return read_u32(cbuf, kCompOffHash);
}

bool component_is_default(int32_t widx, int32_t cidx) {
    uint8_t cbuf[kComponentBufBytes];
    if (!read_component(widx, cidx, cbuf)) return false;
    return read_u32(cbuf, kCompOffActive) != 0;
}

// Within a weapon's component list, find every other component in
// the same bone group and remove it from the ped (unless it's the
// stock default, which the game keeps regardless). Needed because
// the game treats clips/scopes/muzzles as mutually exclusive — fit
// a new one and the old slot must be cleared first or both pile up.
void clear_group(int32_t widx, int32_t cidx, uint32_t group_bone,
                 int32_t ped, uint32_t weapon_hash) {
    const int32_t n_comp = shv::call<int32_t>(
        natives::GET_NUM_DLC_WEAPON_COMPONENTS, widx);
    uint8_t cbuf[kComponentBufBytes];
    for (int32_t c = 0; c < n_comp; ++c) {
        if (c == cidx) continue;
        if (!read_component(widx, c, cbuf)) continue;
        if (read_u32(cbuf, kCompOffBone) != group_bone) continue;
        if (read_u32(cbuf, kCompOffActive) != 0) continue;   // skip stock
        const uint32_t chash = read_u32(cbuf, kCompOffHash);
        if (!shv::call_bool(natives::HAS_PED_GOT_WEAPON_COMPONENT,
                            ped, weapon_hash, chash)) continue;
        shv::call<void>(natives::REMOVE_WEAPON_COMPONENT_FROM_PED,
                        ped, weapon_hash, chash);
    }
}

const char* kErrNotOwned     = "weapon not owned";
const char* kErrNotInShop    = "weapon not in shop list";
const char* kErrOutOfRange   = "component out of range";
const char* kErrDefault      = "default component cannot be removed";
const char* kErrTintRange    = "tint out of range";
const char* kErrNoAmmoAction = "unknown ammo action";
const char* kErrStaleWeapon  = "weapon changed since armory_list";

}  // namespace

namespace armory {

void reset() {
    std::memset(&g_s, 0, sizeof(g_s));
    std::memset(&g_req, 0, sizeof(g_req));
    std::memset(&g_fallback, 0, sizeof(g_fallback));
    g_current_widx = -1;
    g_current_hash = 0;
}

void on_session_reset() {
    // Keep the hash→widx map (the catalogue is build-stable), but
    // drop any pending request + the current-weapon bookkeeping.
    std::memset(&g_req, 0, sizeof(g_req));
    g_current_widx = -1;
    g_current_hash = 0;
    snapshot::clear_armory();
}

void request(uint32_t hash) {
    g_req.pending = true;
    g_req.hash    = hash;
}

void tick(int32_t /*ped*/) {
    if (!g_s.built) advance_session();
}

bool apply(const char* name, uint32_t arg, uint32_t request_hash,
           int32_t ped, char* err, size_t cap) {
    auto fail = [&](const char* msg) {
        std::snprintf(err, cap, "%s", msg);
        return false;
    };

    if (std::strcmp(name, "armory_list") == 0) {
        if (request_hash == 0) return fail("no weapon in args");
        // The session map may still be building; the request is
        // served if the weapon's widx is known OR if the player
        // owns it as a non-catalogue weapon. Both cases are
        // handled inside publish_for().
        if (!publish_for(request_hash, ped)) {
            return fail(kErrNotOwned);
        }
        return true;
    }

    // Every set_* command carries widx in the upper half + a payload
    // in the lower half. Decode consistently; refuse if widx differs
    // from the last-published weapon's widx so a stale frame cannot
    // apply to a different weapon.
    //
    // 0xFFFF on the wire is the client's representation of widx=-1
    // (non-catalogue weapon with fallback components), kept as a
    // positive 16-bit field so the parser never sees a sign bit;
    // convert back to -1 here so a fallback frame's widx matches the
    // plugin's g_current_widx == -1.
    int32_t widx = static_cast<int32_t>((arg >> 16) & 0xFFFF);
    if (widx == 0xFFFF) widx = -1;
    const uint32_t payload = arg & 0xFFFFu;

    if (std::strcmp(name, "set_component") == 0) {
        const int32_t cidx = static_cast<int32_t>((payload >> 1) & 0x7FFF);
        const bool    on   = (payload & 0x1) != 0;
        if (widx != g_current_widx || g_current_hash == 0) {
            return fail(kErrStaleWeapon);
        }

        if (widx >= 0) {
            // DLC catalogue path.
            if (widx_for(g_current_hash) != widx) return fail(kErrNotInShop);
            const int32_t n_comp = shv::call<int32_t>(
                natives::GET_NUM_DLC_WEAPON_COMPONENTS, widx);
            if (cidx < 0 || cidx >= n_comp) return fail(kErrOutOfRange);
            const bool is_default = component_is_default(widx, cidx);
            if (is_default && !on) return fail(kErrDefault);

            const uint32_t chash = component_hash_for(widx, cidx);
            if (chash == 0) return fail(kErrOutOfRange);
            if (on) {
                uint8_t cbuf[kComponentBufBytes];
                if (read_component(widx, cidx, cbuf)) {
                    const uint32_t bone = read_u32(cbuf, kCompOffBone);
                    clear_group(widx, cidx, bone, ped, g_current_hash);
                }
                shv::call<void>(natives::GIVE_WEAPON_COMPONENT_TO_PED,
                                ped, g_current_hash, chash);
            } else {
                shv::call<void>(natives::REMOVE_WEAPON_COMPONENT_FROM_PED,
                                ped, g_current_hash, chash);
            }
            thor_log("[armory] set_component weapon=0x%08X widx=%d cidx=%d on=%d",
                     g_current_hash, widx, cidx, on ? 1 : 0);
            publish_for(g_current_hash, ped);
            return true;
        }

        // Fallback path (widx == -1).
        if (g_fallback.weapon_hash != g_current_hash) return fail(kErrStaleWeapon);
        if (cidx < 0 ||
            static_cast<uint32_t>(cidx) >= g_fallback.count) {
            return fail(kErrOutOfRange);
        }
        const uint32_t chash = g_fallback.hashes[cidx];
        if (g_fallback.is_default[cidx] && !on) return fail(kErrDefault);
        if (on) {
            // Clear any other non-default component in the same bone
            // group so the shop's "one scope / one muzzle at a time"
            // rule holds for fallback weapons too.
            const uint32_t bone = g_fallback.bones[cidx];
            for (uint32_t i = 0; i < g_fallback.count; ++i) {
                if (static_cast<int32_t>(i) == cidx) continue;
                if (g_fallback.bones[i] != bone) continue;
                if (g_fallback.is_default[i]) continue;
                if (!shv::call_bool(natives::HAS_PED_GOT_WEAPON_COMPONENT,
                                    ped, g_current_hash,
                                    g_fallback.hashes[i])) continue;
                shv::call<void>(natives::REMOVE_WEAPON_COMPONENT_FROM_PED,
                                ped, g_current_hash, g_fallback.hashes[i]);
            }
            shv::call<void>(natives::GIVE_WEAPON_COMPONENT_TO_PED,
                            ped, g_current_hash, chash);
        } else {
            shv::call<void>(natives::REMOVE_WEAPON_COMPONENT_FROM_PED,
                            ped, g_current_hash, chash);
        }
        thor_log("[armory] set_component (fallback) weapon=0x%08X cidx=%d "
                 "chash=0x%08X on=%d",
                 g_current_hash, cidx, chash, on ? 1 : 0);
        publish_for(g_current_hash, ped);
        return true;
    }

    if (std::strcmp(name, "set_weapon_tint") == 0) {
        if (g_current_hash == 0) return fail(kErrStaleWeapon);
        if (widx != g_current_widx) return fail(kErrStaleWeapon);
        const int32_t tint  = static_cast<int32_t>(payload);
        const int32_t tints = shv::call<int32_t>(
            natives::GET_WEAPON_TINT_COUNT, g_current_hash);
        if (tint < 0 || tint >= tints) return fail(kErrTintRange);
        shv::call<void>(natives::SET_PED_WEAPON_TINT_INDEX,
                        ped, g_current_hash, tint);
        thor_log("[armory] set_weapon_tint weapon=0x%08X tint=%d",
                 g_current_hash, tint);
        publish_for(g_current_hash, ped);
        return true;
    }

    if (std::strcmp(name, "set_ammo") == 0) {
        if (g_current_hash == 0) return fail(kErrStaleWeapon);
        if (widx != g_current_widx) return fail(kErrStaleWeapon);
        const uint32_t action = payload;
        alignas(8) int64_t max_slot = 0;
        if (!shv::call_bool(natives::GET_MAX_AMMO, ped, g_current_hash,
                            &max_slot)) {
            return fail("no ammo cap for this weapon");
        }
        const int32_t cap_ammo = static_cast<int32_t>(max_slot);
        if (action == 0) {
            // Fill. SET_PED_AMMO takes (ped, hash, int, BOOL); the
            // trailing BOOL is "ignoreMusic" per gta5-mods, we pass
            // 0 like every trainer does.
            shv::call<void>(natives::SET_PED_AMMO,
                            ped, g_current_hash, cap_ammo, 0);
            thor_log("[armory] set_ammo fill weapon=0x%08X -> %d",
                     g_current_hash, cap_ammo);
        } else if (action == 1) {
            // Use the live per-ped clip size so +1 clip tops up by
            // whatever the fitted extended clip provides, not the
            // stock archetype size.
            const int32_t clip = shv::call<int32_t>(
                natives::GET_MAX_AMMO_IN_CLIP, ped, g_current_hash, 1);
            if (clip <= 0) return fail("no clip for this weapon");
            // ADD_AMMO_TO_PED adds without a cap-check; clamp here.
            const int32_t cur = shv::call<int32_t>(
                natives::GET_AMMO_IN_PED_WEAPON, ped, g_current_hash);
            int32_t add = clip;
            if (cur + add > cap_ammo) add = cap_ammo - cur;
            if (add < 0) add = 0;
            if (add > 0) {
                shv::call<void>(natives::ADD_AMMO_TO_PED,
                                ped, g_current_hash, add);
            }
            thor_log("[armory] set_ammo +clip weapon=0x%08X +%d",
                     g_current_hash, add);
        } else {
            return fail(kErrNoAmmoAction);
        }
        publish_for(g_current_hash, ped);
        return true;
    }

    std::snprintf(err, cap, "unknown armory command");
    return false;
}

}  // namespace armory
