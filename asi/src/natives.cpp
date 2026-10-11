// Thin port of GTAIV.EFLC.FusionFix's native-invoke layer for Complete
// Edition 1.2.0.59. See NOTICE for GPL-3.0 attribution.
//
// Strategy follows FusionFix (source/comvars.ixx:3129..3172):
//   * Resolve `getNativeAddress` by scanning for its prologue.
//     Pattern matches twice; take the SECOND hit (the first is a
//     caller stub). Signature: void*(__stdcall)(uint32_t hash) → a
//     void(__cdecl*)(Context*) native handler.
//   * Resolve CTimer::ms_bUserPause / ms_bScriptPause from the pause-
//     gate check pattern. Both must be false before invoking any native.
//
// The CGameProcess per-frame hook that drives this lives in hook.cpp.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstring>

#include "log.h"
#include "natives.h"
#include "patterns.h"

namespace {

// WINAPI == __stdcall on 32-bit Windows (and MinGW honours it).
using GetNativeAddressFn = void* (WINAPI*)(uint32_t hash);

GetNativeAddressFn g_get_native_addr = nullptr;
const bool* g_user_pause = nullptr;
const bool* g_script_pause = nullptr;
bool g_resolved = false;

// FusionFix finds getNativeAddress with `find_pattern<2>(a, b).count(2)
// .get(1)`. Pattern (a) is for classic IV, pattern (b) for EFLC/CE 1.2.
// We only ship (b) because our fingerprint-check already pinned CE
// 1.2.0.59.
//
// Match N==2 and take the second hit: the first is a near-identical
// caller stub FusionFix explicitly rejects (see comvars.ixx:3171).
constexpr const char* kPatGetNativeAddr =
    "56 8B 35 ?? ?? ?? ?? 85 F6 75 06";

// Pause-gate pattern. FusionFix tries two forms (comvars.ixx:3129-3140):
//   short (12 bytes): "0F B6 0D ? ? ? ? 0F B6 C0 0B C1"
//                     ms_bUserPause @+3, ms_bScriptPause @+15  (OUTSIDE match!)
//   long  (19 bytes): "0F B6 0D ? ? ? ? 0F B6 15 ? ? ? ? 33 C0 0B C1"
//                     ms_bUserPause @+3, ms_bScriptPause @+10  (both inside)
// Round 1 of Phase 2 used the SHORT form; `ms_bScriptPause` read from
// +15 landed outside the matched instruction stream and resolved to a
// pointer-1-byte-after-ms_bUserPause (very suspicious). We now try the
// LONG form first and only fall back to the short one, logging a warning
// in that case.
constexpr const char* kPatPauseGateLong =
    "0F B6 0D ?? ?? ?? ?? 0F B6 15 ?? ?? ?? ?? 33 C0 0B C1";
constexpr const char* kPatPauseGateShort =
    "0F B6 0D ?? ?? ?? ?? 0F B6 C0 0B C1";

// Returns the N-th (0-indexed) match of `pat` in .text, or 0 if `pat`
// does not match exactly N+1 times. Scans only once per call — we accept
// the extra pass over .text (<10 ms) for the simpler API.
uintptr_t find_nth(const char* name, const char* pat, int n, int expect) {
    (void)name;
    // patterns::find() refuses on >1 matches. For N!=0 we need a dedicated
    // scanner. Rather than growing patterns::, inline it here.
    HMODULE mod = GetModuleHandleA(NULL);
    if (!mod) return 0;
    const uint8_t* base = reinterpret_cast<const uint8_t*>(mod);

    const IMAGE_DOS_HEADER* dos =
        reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;
    const IMAGE_NT_HEADERS* nt =
        reinterpret_cast<const IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return 0;

    const IMAGE_SECTION_HEADER* sec = IMAGE_FIRST_SECTION(nt);
    const uint8_t* text = nullptr;
    size_t text_sz = 0;
    for (unsigned i = 0; i < nt->FileHeader.NumberOfSections; ++i, ++sec) {
        if (std::memcmp(sec->Name, ".text", 5) == 0) {
            text = base + sec->VirtualAddress;
            text_sz = sec->Misc.VirtualSize;
            break;
        }
    }
    if (!text) return 0;

    // Parse pattern into bytes[]/mask[].
    uint8_t bytes[64] = {};
    uint8_t mask[64]  = {};
    size_t plen = 0;
    const char* p = pat;
    auto hex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    while (*p && plen < sizeof(bytes)) {
        while (*p == ' ' || *p == '\t') ++p;
        if (!*p) break;
        if (p[0] == '?' && p[1] == '?') {
            bytes[plen] = 0; mask[plen] = 0; ++plen; p += 2; continue;
        }
        int h1 = hex(p[0]), h2 = hex(p[1]);
        if (h1 < 0 || h2 < 0) return 0;
        bytes[plen] = static_cast<uint8_t>((h1 << 4) | h2);
        mask[plen]  = 1;
        ++plen;
        p += 2;
    }

    int hits = 0;
    uintptr_t nth_hit = 0;
    for (size_t i = 0; i + plen <= text_sz; ++i) {
        bool ok = true;
        for (size_t j = 0; j < plen; ++j) {
            if (mask[j] && text[i + j] != bytes[j]) { ok = false; break; }
        }
        if (ok) {
            if (hits == n) nth_hit = reinterpret_cast<uintptr_t>(text + i);
            ++hits;
            if (hits > expect) {
                thor_log("[natives] %s: expected %d matches, saw at least %d — bailing",
                         name, expect, hits);
                return 0;
            }
        }
    }
    if (hits != expect) {
        thor_log("[natives] %s: expected %d matches, saw %d",
                 name, expect, hits);
        return 0;
    }
    thor_log("[natives] %s: hit[%d] at 0x%08X", name, n,
             static_cast<unsigned>(nth_hit));
    return nth_hit;
}

}  // namespace

namespace natives {

bool resolve() {
    if (g_resolved) return true;

    // 1. getNativeAddress — second of two matches.
    uintptr_t gna = find_nth("getNativeAddress", kPatGetNativeAddr, 1, 2);
    if (!gna) return false;
    g_get_native_addr = reinterpret_cast<GetNativeAddressFn>(gna);

    // 2. Pause gate — prefer the LONG form (both pointers inside the
    //    matched block); fall back to the short form only if the long
    //    one misses.
    uintptr_t gate = patterns::find("ms_bPause gate (long)", kPatPauseGateLong);
    int script_pause_off = 10;
    if (!gate) {
        thor_log("[natives] long pause-gate missed; trying short form");
        gate = patterns::find("ms_bPause gate (short)", kPatPauseGateShort);
        script_pause_off = 15;  // outside the matched instruction stream
        if (!gate) return false;
    }
    g_user_pause   = *reinterpret_cast<const bool* const*>(gate + 3);
    g_script_pause = *reinterpret_cast<const bool* const*>(gate + script_pause_off);
    thor_log("[natives] ms_bUserPause=0x%08X ms_bScriptPause=0x%08X (gate+%d)",
             static_cast<unsigned>(reinterpret_cast<uintptr_t>(g_user_pause)),
             static_cast<unsigned>(reinterpret_cast<uintptr_t>(g_script_pause)),
             script_pause_off);

    // Sanity: the two pointers should be in the same general region of
    // the exe's data segment, but they are independent globals — being
    // 1 byte apart is a strong "something is wrong" smell.
    uintptr_t up = reinterpret_cast<uintptr_t>(g_user_pause);
    uintptr_t sp = reinterpret_cast<uintptr_t>(g_script_pause);
    if (up == sp || (up < sp && sp - up < 4) || (sp < up && up - sp < 4)) {
        thor_log("[natives] WARNING: pause pointers suspiciously close (%u bytes apart)",
                 static_cast<unsigned>(up > sp ? up - sp : sp - up));
    }

    g_resolved = true;

    // Pre-warm the handler cache so per-frame invokes never call
    // getNativeAddress mid-transition.
    static const uint32_t kPrewarm[] = {
        GET_PLAYER_ID, GET_PLAYER_CHAR,
        GET_CHAR_HEALTH, GET_CHAR_ARMOUR,
        STORE_SCORE, STORE_WANTED_LEVEL,
        GET_CURRENT_CHAR_WEAPON, GET_AMMO_IN_CHAR_WEAPON,
        GET_AMMO_IN_CLIP, GET_CHAR_WEAPON_IN_SLOT,
        IS_CHAR_IN_ANY_CAR, GET_CAR_CHAR_IS_USING,
        GET_CAR_SPEED, GET_ENGINE_HEALTH,
        GET_CAR_MODEL, GET_DISPLAY_NAME_FROM_VEHICLE_MODEL,
        GET_PLAYER_RADIO_STATION_INDEX,
        SET_CURRENT_CHAR_WEAPON, RETUNE_RADIO_TO_STATION_INDEX,
    };
    unsigned ok_count = 0;
    for (uint32_t h : kPrewarm) {
        if (lookup(h)) ++ok_count;
    }
    thor_log("[natives] handler cache primed: %u/%u", ok_count,
             static_cast<unsigned>(sizeof(kPrewarm) / sizeof(kPrewarm[0])));

    return true;
}

bool ok_to_invoke() {
    if (!g_resolved) return false;
    if (!g_user_pause || !g_script_pause) return false;
    return !*g_user_pause && !*g_script_pause;
}

// Fixed-size open-addressing cache for native handlers. Tiny: GTA IV
// has ~1500 natives but we only invoke ~15 per frame; linear scan over a
// 32-entry array is faster than a hash probe.
namespace {
struct CacheEntry { uint32_t hash; natives::Handler fn; };
CacheEntry g_cache[32] = {};
unsigned   g_cache_count = 0;
}  // namespace

Handler lookup(uint32_t hash) {
    if (!g_resolved) return nullptr;
    for (unsigned i = 0; i < g_cache_count; ++i) {
        if (g_cache[i].hash == hash) return g_cache[i].fn;
    }
    if (g_cache_count >= sizeof(g_cache) / sizeof(g_cache[0])) return nullptr;
    void* h = g_get_native_addr(hash);
    if (!h) {
        thor_log("[natives] lookup(0x%08X): game returned null", hash);
        return nullptr;
    }
    g_cache[g_cache_count].hash = hash;
    g_cache[g_cache_count].fn   = reinterpret_cast<Handler>(h);
    ++g_cache_count;
    return reinterpret_cast<Handler>(h);
}

bool invoke(uint32_t hash, Context& ctx) {
    if (!ok_to_invoke()) return false;
    Handler fn = lookup(hash);
    if (!fn) return false;
    fn(&ctx);
    return true;
}

}  // namespace natives
