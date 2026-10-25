#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

#include "armory_probe.h"
#include "joaat.h"
#include "log.h"
#include "natives.h"
#include "shv.h"

namespace {

// 7 ints as 8-byte script slots then 4 x 64-byte label buffers; the
// hex dump is the authoritative record and these offsets are a
// best-guess parse for a one-liner summary at the end of each weapon.
constexpr size_t kWeaponBufBytes    = 312;
constexpr size_t kComponentBufBytes = 176;
constexpr size_t kWeaponOffHash     = 0x08;
constexpr size_t kWeaponOffClip     = 0x30;
constexpr size_t kWeaponOffNameLbl  = 0x38;
constexpr size_t kCompOffBone       = 0x00;
constexpr size_t kCompOffActive     = 0x08;
constexpr size_t kCompOffHash       = 0x18;
constexpr size_t kCompOffCost       = 0x28;
constexpr size_t kCompOffNameLbl    = 0x30;

// Never walk more than this; a real catalogue is a few dozen entries
// and anything larger is almost certainly a wrong-build mismatch, not
// a legitimate list.
constexpr int kMaxScan = 256;

constexpr uint32_t kTargetPistol    = joaat("weapon_pistol");
constexpr uint32_t kTargetCarbine   = joaat("weapon_carbinerifle");
constexpr uint32_t kTargetPistolMk2 = joaat("weapon_pistol_mk2");
constexpr uint32_t kTargetKnife     = joaat("weapon_knife");

void hex_dump(const uint8_t* buf, size_t n, const char* tag) {
    char line[80];
    for (size_t off = 0; off < n; off += 16) {
        const size_t stop = (off + 16 > n) ? n : off + 16;
        int w = 0;
        for (size_t i = off; i < stop && w < static_cast<int>(sizeof(line)) - 4; ++i) {
            w += std::snprintf(line + w,
                               sizeof(line) - static_cast<size_t>(w),
                               "%02X ", buf[i]);
        }
        thor_log("[armory] %s +0x%03X  %s", tag,
                 static_cast<unsigned>(off), line);
    }
}

const char* label_text(const char* key) {
    if (!key || !key[0]) return "(empty)";
    const char* text = shv::call<const char*>(natives::GET_LABEL_TEXT, key);
    if (!text || !text[0] || std::strcmp(text, "NULL") == 0) return "(no entry)";
    return text;
}

bool read_weapon(int idx, uint8_t* buf) {
    std::memset(buf, 0, kWeaponBufBytes);
    return shv::call_bool(natives::GET_DLC_WEAPON_DATA, idx, buf);
}

uint32_t read_u32(const uint8_t* buf, size_t off) {
    uint32_t v = 0;
    std::memcpy(&v, buf + off, sizeof(v));
    return v;
}

void dump_full(int idx, int32_t ped) {
    uint8_t buf[kWeaponBufBytes];
    if (!read_weapon(idx, buf)) {
        thor_log("[armory] idx=%d GET_DLC_WEAPON_DATA -> false", idx);
        return;
    }

    char tag[16];
    std::snprintf(tag, sizeof(tag), "w%d", idx);
    hex_dump(buf, kWeaponBufBytes, tag);

    const uint32_t hash         = read_u32(buf, kWeaponOffHash);
    const uint32_t clip_in_buf  = read_u32(buf, kWeaponOffClip);
    const char*    name_key     = reinterpret_cast<const char*>(buf + kWeaponOffNameLbl);
    const int      tints        = shv::call<int>(natives::GET_WEAPON_TINT_COUNT, hash);
    const int      clip_native  = shv::call<int>(natives::GET_WEAPON_CLIP_SIZE, hash);
    const bool     owned        = ped
        ? shv::call_bool(natives::HAS_PED_GOT_WEAPON, ped, hash, 0)
        : false;

    thor_log("[armory] idx=%d hash=0x%08X clip_in_buf=%u name_key='%.63s' "
             "label='%s' tints=%d clip_native=%d owned=%d",
             idx, hash, clip_in_buf, name_key, label_text(name_key),
             tints, clip_native, owned ? 1 : 0);

    const int n_comp = shv::call<int>(natives::GET_NUM_DLC_WEAPON_COMPONENTS, idx);
    thor_log("[armory] idx=%d component_count=%d", idx, n_comp);
    for (int c = 0; c < n_comp; ++c) {
        uint8_t cbuf[kComponentBufBytes];
        std::memset(cbuf, 0, sizeof(cbuf));
        if (!shv::call_bool(natives::GET_DLC_WEAPON_COMPONENT_DATA, idx, c, cbuf)) {
            thor_log("[armory] idx=%d c=%d GET_DLC_WEAPON_COMPONENT_DATA -> false",
                     idx, c);
            continue;
        }
        char ctag[20];
        std::snprintf(ctag, sizeof(ctag), "w%dc%d", idx, c);
        hex_dump(cbuf, kComponentBufBytes, ctag);

        const uint32_t bone   = read_u32(cbuf, kCompOffBone);
        const uint32_t active = read_u32(cbuf, kCompOffActive);
        const uint32_t chash  = read_u32(cbuf, kCompOffHash);
        const uint32_t cost   = read_u32(cbuf, kCompOffCost);
        const char*    ckey   = reinterpret_cast<const char*>(cbuf + kCompOffNameLbl);
        const bool     takes  = shv::call_bool(
            natives::DOES_WEAPON_TAKE_WEAPON_COMPONENT, hash, chash);
        const bool     has    = ped
            ? shv::call_bool(natives::HAS_PED_GOT_WEAPON_COMPONENT, ped, hash, chash)
            : false;

        thor_log("[armory] idx=%d c=%d bone=%u default=%u chash=0x%08X cost=%u "
                 "key='%.63s' label='%s' takes=%d has=%d",
                 idx, c, bone, active, chash, cost, ckey, label_text(ckey),
                 takes ? 1 : 0, has ? 1 : 0);
    }
}

}  // namespace

namespace armory_probe {

void run(int32_t ped) {
    const int reported = shv::call<int>(natives::GET_NUM_DLC_WEAPONS);
    const int total = (reported < 0) ? 0
                    : (reported > kMaxScan) ? kMaxScan : reported;

    thor_log("[armory] === GATE A0 PROBE START ===");
    thor_log("[armory] GET_NUM_DLC_WEAPONS reported=%d scanned=%d",
             reported, total);

    // Pass 1: enumerate every reported index, log its weapon hash, and
    // remember which ones match the four target weapons. No hex dumps
    // here — the log stays scannable at a glance.
    int idx_pistol = -1, idx_carbine = -1, idx_pistol_mk2 = -1, idx_knife = -1;
    for (int i = 0; i < total; ++i) {
        uint8_t buf[kWeaponBufBytes];
        if (!read_weapon(i, buf)) {
            thor_log("[armory] scan idx=%d <no data>", i);
            continue;
        }
        const uint32_t hash = read_u32(buf, kWeaponOffHash);
        if (hash == kTargetPistol)    idx_pistol    = i;
        if (hash == kTargetCarbine)   idx_carbine   = i;
        if (hash == kTargetPistolMk2) idx_pistol_mk2 = i;
        if (hash == kTargetKnife)     idx_knife     = i;
        thor_log("[armory] scan idx=%d hash=0x%08X", i, hash);
    }
    thor_log("[armory] targets: pistol=%d carbine=%d pistol_mk2=%d knife=%d "
             "(-1 = not found at any DLC index)",
             idx_pistol, idx_carbine, idx_pistol_mk2, idx_knife);

    // Pass 2: full dump for the first 5 indices and each resolved
    // target, deduplicated so the same weapon is not dumped twice when
    // its target role falls inside the first 5.
    bool done[kMaxScan] = {false};
    auto dump_once = [&](int i) {
        if (i < 0 || i >= total || done[i]) return;
        done[i] = true;
        dump_full(i, ped);
    };
    for (int i = 0; i < 5 && i < total; ++i) dump_once(i);
    dump_once(idx_pistol);
    dump_once(idx_carbine);
    dump_once(idx_pistol_mk2);
    dump_once(idx_knife);

    thor_log("[armory] === GATE A0 PROBE END ===");
}

}  // namespace armory_probe
