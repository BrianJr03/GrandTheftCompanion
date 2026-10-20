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
    GET_CURRENT_EPISODE                 = 0x7D7619D2,  // 0=IV, 1=TLAD, 2=TBoGT
    IS_BUTTON_PRESSED                   = 0x7FF21081,  // bool (padIndex, button)

    // Map blips and the GPS route (natives level 5). Signatures from
    // FusionFix natives.ixx; see blip_natives.h. Unlike GTA V, IV's
    // FIRST/NEXT walk is keyed by blip *type* (car, char, coord,
    // contact, pickup...), not by sprite, and there is no native that
    // sets the player's waypoint: SET_ROUTE draws the GPS line to an
    // existing blip instead.
    GET_FIRST_BLIP_INFO_ID              = 0x3BD729E9,  // Blip (uint type)
    GET_NEXT_BLIP_INFO_ID               = 0x154932F0,  // Blip (uint type)
    DOES_BLIP_EXIST                     = 0x590A6FF4,  // bool (Blip)
    GET_BLIP_COORDS                     = 0x4C1E75DB,  // void (Blip, Vector3* out)
    GET_BLIP_SPRITE                     = 0x30B1316B,  // uint (Blip)
    GET_BLIP_INFO_ID_DISPLAY            = 0x1B731C3F,  // uint (Blip)
    GET_BLIP_INFO_ID_TYPE               = 0x6A9E5CE5,  // uint (Blip)
    GET_BLIP_COLOUR                     = 0x59B425DA,  // void (Blip, uint* colour)
    GET_CHAR_COORDINATES                = 0x2B5C06E6,  // void (Ped, float* x, float* y, float* z)
    GET_MISSION_FLAG                    = 0x2BC64736,  // bool ()
    SET_ROUTE                           = 0x7B8D68E7,  // void (Blip, bool on)

    // Cheat writes: health, wanted, loadout, vehicle, weather, clock,
    // player toggles. Hashes verified against FusionFix source/natives.ixx.
    // Only compiled into runtime use when THORBRIDGE_CHEATS is on; the
    // hashes themselves are declared unconditionally so the natives::
    // lookup cache keeps a single stable layout across builds.
    //
    // Native cache budget: 32 entries used today across stats/weapon/
    // vehicle/radio/blips + 19 here = 51, inside natives::g_cache[64].
    SET_CHAR_HEALTH                     = 0x575E2880,  // void (Ped, uint health)
    ADD_ARMOUR_TO_CHAR                  = 0x1C623537,  // void (Ped, uint amount)
    ALTER_WANTED_LEVEL                  = 0x60C80EC9,  // void (Player, uint level)
    APPLY_WANTED_LEVEL_CHANGE_NOW       = 0x705A6ED9,  // void (Player)
    CLEAR_WANTED_LEVEL                  = 0x205622AC,  // void (Player)
    GET_MAX_WANTED_LEVEL                = 0x71755E9B,  // void (uint* out)
    GIVE_WEAPON_TO_CHAR                 = 0x03E90416,  // void (Ped, uint weapon, uint ammo, bool)
    SET_CHAR_AMMO                       = 0x437D247E,  // void (Ped, uint weapon, uint ammo)
    FIX_CAR                             = 0x3D562F78,  // void (Vehicle)
    WASH_VEHICLE_TEXTURES               = 0x69491CFA,  // void (Vehicle, uint intensity)
    FORCE_WEATHER_NOW                   = 0x63737D31,  // void (uint weather)
    RELEASE_WEATHER                     = 0x3A115D9D,  // void ()
    GET_TIME_OF_DAY                     = 0x384B3876,  // void (uint* hour, uint* minute)
    SET_TIME_OF_DAY                     = 0x52100540,  // void (uint hour, uint minute)
    SET_PLAYER_INVINCIBLE               = 0x7E9E02E1,  // void (Player, bool)
    SET_PLAYER_FAST_RELOAD              = 0x29B53DFF,  // void (Player, bool)
    SET_PLAYER_NEVER_GETS_TIRED         = 0x0DDC19F4,  // void (Player, bool)
    SET_TIME_SCALE                      = 0x24D467CC,  // void (float)
    SET_POLICE_IGNORE_PLAYER            = 0x619D51D3,  // void (Player, bool)
};

// rage::scrNativeCallContext layout, per FusionFix (source/natives.ixx).
// All natives are `void (__cdecl)(Context*)`. The handler reads args
// from m_pArgs, writes return to m_pReturn. Critically, both pointers
// point into the SAME storage — m_TemporaryData — so direct-return
// natives like GET_PLAYER_ID write their result into slot 0 of this
// buffer. Round-5 bug: I had a separate `m_scratch` that m_pArgs /
// m_pReturn pointed at, which meant direct returns landed in
// m_TemporaryData while get_u32() read from m_scratch — always zero.
struct Context {
    void*     m_pReturn;             // offset 0
    uint32_t  m_nArgCount;           // offset 4
    void*     m_pArgs;               // offset 8
    uint32_t  m_nDataCount;          // offset 12
    void*     m_pOriginalData[4];    // offset 16 — Vector3*[4]; unused by us
    uint32_t  m_TemporaryData[16];   // offset 32 — the shared arg+return scratch

    Context() { reset(); }

    void reset() {
        m_nArgCount = 0;
        m_nDataCount = 0;
        m_pArgs   = m_TemporaryData;
        m_pReturn = m_TemporaryData;
        // Zero the originals; some handlers inspect them unconditionally.
        for (int i = 0; i < 4; ++i) m_pOriginalData[i] = nullptr;
        for (int i = 0; i < 16; ++i) m_TemporaryData[i] = 0;
    }

    // Push a 4-byte argument. uint32_t covers int32_t, uint32_t, handles,
    // and raw bit-casts of float (use push_float for floats).
    void push(uint32_t v) {
        m_TemporaryData[m_nArgCount++] = v;
    }
    void push(int32_t v) { push(static_cast<uint32_t>(v)); }
    void push(void* p)   { push(reinterpret_cast<uint32_t>(p)); }

    void push_float(float f) {
        union { float f; uint32_t u; } cvt{f};
        push(cvt.u);
    }

    // Read the native's return value out of slot 0 of the scratch.
    uint32_t get_u32() const { return m_TemporaryData[0]; }
    int32_t  get_i32() const { return static_cast<int32_t>(m_TemporaryData[0]); }
    bool     get_bool() const { return m_TemporaryData[0] != 0; }
    float    get_float() const {
        union { uint32_t u; float f; } cvt{m_TemporaryData[0]};
        return cvt.f;
    }
    void*    get_ptr() const {
        return reinterpret_cast<void*>(m_TemporaryData[0]);
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

// Resolve a native handler once and cache it. Returns nullptr if
// resolve() hasn't succeeded or the hash isn't known to the game.
// Subsequent calls with the same hash return the cached pointer.
using Handler = void (*)(Context*);
Handler lookup(uint32_t hash);

// Diagnostic — read the raw pause-gate bytes so we can verify from the
// log that the resolved pointers transition sensibly during gameplay.
// Returns false if resolve() hasn't succeeded.
bool debug_read_pause(bool* user_paused, bool* script_paused);

}  // namespace natives
