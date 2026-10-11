#pragma once

#include <cstdint>

// natives:: — call GTA IV script-engine natives from inside the running
// game process. Thin port of GTAIV.EFLC.FusionFix's native-invoke layer
// (source/natives.ixx and comvars.ixx), GPL-3.0; see NOTICE.
//
// Usage:
//     if (!natives::resolve()) return;              // once, at startup
//     natives::Context ctx;
//     ctx.push(player_id);
//     uint32_t ped_handle = 0;
//     ctx.push_out(&ped_handle);
//     natives::invoke(natives::GET_PLAYER_CHAR, ctx);
//
// Every call MUST happen on the game's main/script thread (we drive this
// from the CGameProcess hook) and only when CTimer::ms_bScriptPause and
// CTimer::ms_bUserPause are both false. See natives::ok_to_invoke().

namespace natives {

// Hashes — from FusionFix source/natives.ixx (which traces to Zolika1351's
// iv-sdk). All verified against the FusionFix enum.
enum : uint32_t {
    GET_PLAYER_ID                       = 0x62E319C6,
    GET_PLAYER_CHAR                     = 0x511454A9,
    GET_CHAR_HEALTH                     = 0x4B6C2256,
    GET_CHAR_ARMOUR                     = 0x3C756E54,
    STORE_SCORE                         = 0x1E203014,
    STORE_WANTED_LEVEL                  = 0x12AA6D71,
    GET_CURRENT_CHAR_WEAPON             = 0x5AB8289F,
    GET_AMMO_IN_CHAR_WEAPON             = 0x23E140A9,
    GET_AMMO_IN_CLIP                    = 0x612C748F,
    GET_CHAR_WEAPON_IN_SLOT             = 0x74EC7580,
    IS_CHAR_IN_ANY_CAR                  = 0x71184DA3,
    GET_CAR_CHAR_IS_USING               = 0x1B067237,
    GET_CAR_SPEED                       = 0x16DD2D00,
    GET_ENGINE_HEALTH                   = 0x2B0A05E0,
    GET_CAR_MODEL                       = 0x5FF84497,
    GET_DISPLAY_NAME_FROM_VEHICLE_MODEL = 0x404E0056,
    GET_PLAYER_RADIO_STATION_INDEX      = 0x4E493AAF,
    SET_CURRENT_CHAR_WEAPON             = 0x6CF44DD6,
    RETUNE_RADIO_TO_STATION_INDEX       = 0x48ED6432,
};

// rage::scrNativeCallContext layout as FusionFix observes it
// (source/natives.ixx:64..). All natives are `void (__cdecl)(Context*)`:
// their handler reads args from m_pArgs, writes a return into m_pReturn
// (which we point at the same 16-slot scratch, so return reads come out
// of slot 0). Fixed size — natives take at most 16 4-byte args in IV.
struct Context {
    void*     m_pReturn;
    uint32_t  m_nArgCount;
    void*     m_pArgs;
    uint32_t  m_nDataCount;
    void*     m_pOriginalData[4];   // Vector3*[4]; we never touch these
    float     m_TemporaryData[16];  // Vector4[4] = 16 floats = 64 bytes

    // The actual arg/return scratch. 16 slots of 4 bytes each. Reset by
    // reset(); push() appends.
    uint32_t  m_scratch[16];

    Context() { reset(); }

    void reset() {
        m_nArgCount = 0;
        m_nDataCount = 0;
        m_pArgs = m_scratch;
        m_pReturn = m_scratch;
    }

    // Push a 4-byte argument. uint32_t covers int32_t, uint32_t, handles,
    // and raw bit-casts of float (use push_float for floats).
    void push(uint32_t v) {
        m_scratch[m_nArgCount++] = v;
    }
    void push(int32_t v) { push(static_cast<uint32_t>(v)); }
    void push(void* p)   { push(reinterpret_cast<uint32_t>(p)); }

    void push_float(float f) {
        union { float f; uint32_t u; } cvt{f};
        push(cvt.u);
    }

    // Read the native's return value out of scratch slot 0. Floats come
    // back via the same slot (the handler stores st0 → first scratch
    // slot before returning in FusionFix's observed behaviour).
    uint32_t get_u32() const { return m_scratch[0]; }
    int32_t  get_i32() const { return static_cast<int32_t>(m_scratch[0]); }
    bool     get_bool() const { return m_scratch[0] != 0; }
    float    get_float() const {
        union { uint32_t u; float f; } cvt{m_scratch[0]};
        return cvt.f;
    }
    void*    get_ptr() const {
        return reinterpret_cast<void*>(m_scratch[0]);
    }
};

// Resolve getNativeAddress + the pause-gate booleans from the running
// exe. Idempotent. Returns true iff all required symbols were found; a
// return of false leaves natives:: unusable and the ASI stays in safe
// mode. All failures are logged.
bool resolve();

// Pause-gate check. Natives are only safe to call when the script engine
// isn't paused (menus, cutscene fades, save/load screens). resolve()
// must have returned true for this to be meaningful; it returns false
// otherwise so callers degrade safely.
bool ok_to_invoke();

// Dispatch a native. Caller sets up ctx with push()es first. Does
// nothing (returns false) if resolve() hasn't succeeded or
// ok_to_invoke() is false, so this is also safe to call from a game
// thread that might be mid-pause.
bool invoke(uint32_t hash, Context& ctx);

}  // namespace natives
