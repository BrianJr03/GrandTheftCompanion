// Phase 2 probes orchestrator for GTA IV Complete Edition 1.2.0.59.
//
// Lifecycle (one worker thread):
//   1. Poll for the "code decrypted & mapped" gate pattern. Modelled on
//      FusionFix's CallbackHandler::RegisterCallback usage
//      (source/dllmain.cpp:142) — see NOTICE.
//   2. Call natives::resolve() to lock onto getNativeAddress + the
//      pause-gate booleans.
//   3. Call hook::install_cgameprocess(on_frame) to detour the per-frame
//      tick. All subsequent native reads happen on that thread.
//
// Per frame (on_frame runs on the game thread):
//   * Skip if natives::ok_to_invoke() is false (menu, cutscene, pause).
//   * GET_PLAYER_ID + GET_PLAYER_CHAR to confirm a loaded save.
//   * Read health / armor / money / wanted / weapon / ammo via natives.
//   * If IS_CHAR_IN_ANY_CAR, also read vehicle model / speed / engine.
//   * Read radio-station index (meaningful mostly in vehicles).
//   * Build a snapshot::Player and publish().

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstring>

#include "hook.h"
#include "log.h"
#include "natives.h"
#include "patterns.h"
#include "probes.h"
#include "snapshot.h"

// Round-2 safety: the per-frame hook is only installed if this is set
// at build time. Default OFF after Phase-2 round 1 crashed Brian's
// save-load four times. Build with -DTHORBRIDGE_ENABLE_HOOK=1 once the
// natives + hook changes have been reviewed and we're ready to retry
// on-device.
#ifndef THORBRIDGE_ENABLE_HOOK
#define THORBRIDGE_ENABLE_HOOK 0
#endif

namespace {

// Post-decryption gate. FusionFix uses this same signature as the
// trigger for its Init(): once this instruction is in .text, the rest
// of the exe is settled enough to pattern-scan safely.
constexpr const char* kPatInitGate =
    "F3 0F 10 44 24 ?? F3 0F 59 05 ?? ?? ?? ?? EB ?? E8";

bool g_enabled = false;

// Spin-wait for the init gate. FusionFix polls at 1s intervals; we do
// 500ms to feel more responsive on cold starts. Caps out at 60s so an
// unsupported build gives up rather than hanging forever.
bool wait_for_init_gate() {
    for (int i = 0; i < 120; ++i) {
        if (patterns::find("init-gate", kPatInitGate)) {
            thor_log("[probes] init-gate matched after %d poll(s)", i + 1);
            return true;
        }
        Sleep(500);
    }
    thor_log("[probes] init-gate never matched; giving up after 60s");
    return false;
}

// Per-frame tick. Runs on the game's main thread AFTER the game's own
// CGameProcess completes.
void on_frame() {
    if (!natives::ok_to_invoke()) {
        snapshot::Player p{};
        p.ts_ms = GetTickCount();
        snapshot::publish(p);
        return;
    }

    natives::Context ctx;

    // GET_PLAYER_ID() — direct uint32_t return.
    ctx.reset();
    if (!natives::invoke(natives::GET_PLAYER_ID, ctx)) return;
    uint32_t player_id = ctx.get_u32();
    // GTA IV single-player returns 0 for the local player. Values other
    // than 0 or a plausible small index are a strong signal that the
    // engine is mid-transition — bail without touching anything else.
    if (player_id == 0xFFFFFFFFu || player_id > 32) {
        snapshot::Player p{};
        p.ts_ms = GetTickCount();
        snapshot::publish(p);
        return;
    }

    // GET_PLAYER_CHAR(player, &ped_handle) — ped written to out ptr.
    uint32_t ped_handle = 0;
    ctx.reset();
    ctx.push(player_id);
    ctx.push(&ped_handle);
    natives::invoke(natives::GET_PLAYER_CHAR, ctx);
    // Script-engine handles are strictly positive 20-bit integers (upper
    // bits are generation/pool metadata); 0 or 0xFFFFFFFF means "none".
    if (ped_handle == 0 || ped_handle == 0xFFFFFFFFu) {
        snapshot::Player p{};
        p.ts_ms = GetTickCount();
        snapshot::publish(p);
        return;
    }

    snapshot::Player p{};
    p.in_game = true;
    p.ts_ms = GetTickCount();

    // Health. uint32_t out param; IV reports 100..200 where 100 is dead.
    {
        uint32_t out = 0;
        ctx.reset(); ctx.push(ped_handle); ctx.push(&out);
        natives::invoke(natives::GET_CHAR_HEALTH, ctx);
        p.health = static_cast<float>(out);
    }
    // Armor.
    {
        uint32_t out = 0;
        ctx.reset(); ctx.push(ped_handle); ctx.push(&out);
        natives::invoke(natives::GET_CHAR_ARMOUR, ctx);
        p.armor = static_cast<uint16_t>(out);
    }
    // Money / score.
    {
        uint32_t out = 0;
        ctx.reset(); ctx.push(player_id); ctx.push(&out);
        natives::invoke(natives::STORE_SCORE, ctx);
        p.money = out;
    }
    // Wanted level.
    {
        uint32_t out = 0;
        ctx.reset(); ctx.push(player_id); ctx.push(&out);
        natives::invoke(natives::STORE_WANTED_LEVEL, ctx);
        p.wanted_level = static_cast<uint8_t>(out);
    }
    // Current weapon.
    {
        uint32_t out = 0;
        ctx.reset(); ctx.push(ped_handle); ctx.push(&out);
        natives::invoke(natives::GET_CURRENT_CHAR_WEAPON, ctx);
        p.weapon_id = static_cast<int32_t>(out);
    }
    // Ammo in current weapon (total) and in current clip.
    {
        uint32_t out = 0;
        ctx.reset(); ctx.push(ped_handle);
        ctx.push(static_cast<uint32_t>(p.weapon_id));
        ctx.push(&out);
        natives::invoke(natives::GET_AMMO_IN_CHAR_WEAPON, ctx);
        p.ammo_total = static_cast<int32_t>(out);

        out = 0;
        ctx.reset(); ctx.push(ped_handle);
        ctx.push(static_cast<uint32_t>(p.weapon_id));
        ctx.push(&out);
        natives::invoke(natives::GET_AMMO_IN_CLIP, ctx);
        p.ammo_clip = static_cast<int32_t>(out);
    }
    // Owned weapons across all 13 slots. ~13 extra natives/frame is
    // enough that we throttle to roughly once a second — the HUD only
    // uses this for the picker, not twitch feedback. We reuse the last
    // scanned values across in-between frames via a module-local cache.
    static uint32_t s_slot_frame = 0;
    static int32_t  s_cached_weapons[13] = {};
    static int32_t  s_cached_ammo[13]    = {};
    if ((s_slot_frame++ % 30) == 0) {
        for (int slot = 0; slot < 13; ++slot) {
            uint32_t weapon = 0, ammo0 = 0, ammo1 = 0;
            ctx.reset();
            ctx.push(ped_handle);
            ctx.push(static_cast<uint32_t>(slot));
            ctx.push(&weapon);
            ctx.push(&ammo0);
            ctx.push(&ammo1);
            natives::invoke(natives::GET_CHAR_WEAPON_IN_SLOT, ctx);
            s_cached_weapons[slot] = static_cast<int32_t>(weapon);
            s_cached_ammo[slot]    = static_cast<int32_t>(ammo0);
        }
    }
    std::memcpy(p.owned_weapons, s_cached_weapons, sizeof(p.owned_weapons));
    std::memcpy(p.owned_ammo,    s_cached_ammo,    sizeof(p.owned_ammo));
    // Vehicle (iff in one).
    {
        ctx.reset(); ctx.push(ped_handle);
        natives::invoke(natives::IS_CHAR_IN_ANY_CAR, ctx);
        p.in_vehicle = ctx.get_bool();
    }
    if (p.in_vehicle) {
        uint32_t veh = 0;
        ctx.reset(); ctx.push(ped_handle); ctx.push(&veh);
        natives::invoke(natives::GET_CAR_CHAR_IS_USING, ctx);
        if (veh != 0) {
            // Model id (uint32_t out).
            uint32_t model = 0;
            ctx.reset(); ctx.push(veh); ctx.push(&model);
            natives::invoke(natives::GET_CAR_MODEL, ctx);
            p.vehicle_model = model;

            // Display name — char* direct return pointer into the game's
            // string table. Copy out — never free.
            ctx.reset(); ctx.push(model);
            natives::invoke(natives::GET_DISPLAY_NAME_FROM_VEHICLE_MODEL,
                             ctx);
            const char* name = static_cast<const char*>(ctx.get_ptr());
            if (name) {
                std::strncpy(p.vehicle_name, name, sizeof(p.vehicle_name) - 1);
                p.vehicle_name[sizeof(p.vehicle_name) - 1] = '\0';
            }

            // Speed — float out param.
            {
                float out = 0.0f;
                ctx.reset(); ctx.push(veh); ctx.push(&out);
                natives::invoke(natives::GET_CAR_SPEED, ctx);
                p.vehicle_speed_mps = out;
            }
            // Engine health — direct float return.
            ctx.reset(); ctx.push(veh);
            natives::invoke(natives::GET_ENGINE_HEALTH, ctx);
            p.vehicle_engine_hp = ctx.get_float();
        }
    }
    // Radio station index — direct uint32_t return. -1 (0xFFFFFFFF) when
    // player isn't near a radio source; map to int32 so the HUD can
    // special-case "off".
    {
        ctx.reset();
        natives::invoke(natives::GET_PLAYER_RADIO_STATION_INDEX, ctx);
        p.radio_station = static_cast<int32_t>(ctx.get_u32());
    }

    snapshot::publish(p);
}

DWORD WINAPI startup_worker(LPVOID /*unused*/) {
    if (!wait_for_init_gate()) return 0;
    if (!natives::resolve()) {
        thor_log("[probes] natives::resolve() failed; staying in safe mode");
        return 0;
    }
#if THORBRIDGE_ENABLE_HOOK
    if (!hook::install_cgameprocess(on_frame)) {
        thor_log("[probes] CGameProcess hook install failed; staying in safe mode");
        return 0;
    }
    g_enabled = true;
    thor_log("[probes] enabled with per-frame hook");
#else
    (void)on_frame;  // silence -Wunused when hook is off
    thor_log("[probes] natives resolved, hook disabled by build flag "
             "(THORBRIDGE_ENABLE_HOOK=0) — safe mode");
#endif
    return 0;
}

}  // namespace

namespace probes {

void start_async() {
    HANDLE th = CreateThread(NULL, 0, startup_worker, NULL, 0, NULL);
    if (th) CloseHandle(th);
}

bool enabled() { return g_enabled; }

}  // namespace probes
