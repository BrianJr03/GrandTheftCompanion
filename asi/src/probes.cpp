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

#include "blips.h"
#include "commands.h"
#include "hook.h"
#include "log.h"
#include "natives.h"
#include "patterns.h"
#include "probes.h"
#include "snapshot.h"
#include "weapons.h"
#if THORBRIDGE_CHEATS
#include "cheat_natives.h"
#include "cheats.h"
#endif

// Round-2 safety: the per-frame hook is only installed if this is set
// at build time. Default OFF after Phase-2 round 1 crashed Brian's
// save-load four times.
#ifndef THORBRIDGE_ENABLE_HOOK
#define THORBRIDGE_ENABLE_HOOK 0
#endif

// Round-4 isolation: when set alongside THORBRIDGE_ENABLE_HOOK=1, the
// per-frame hook installs and fires every frame, but on_frame does
// NOT invoke any natives — it just publishes an empty snapshot. This
// cleanly separates "is the CALL-overwrite trampoline unsafe under
// Wine?" from "are my native invocations unsafe?".
#ifndef THORBRIDGE_HOOK_NO_NATIVES
#define THORBRIDGE_HOOK_NO_NATIVES 0
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

// Round-5 stability gate. GET_PLAYER_ID must return the SAME small-
// positive value this many frames in a row before we trust the engine
// enough to chain further natives. Round-1 crashes happened during the
// save-load transition when values were in flux; this blocks that
// window without needing to trust ms_bScriptPause (which stayed 0
// through Brian's save-load in round 3).
constexpr unsigned kStableFramesRequired = 15;  // ~0.25s at 60fps

// Which native-group is enabled for this build. Round 5 starts narrow
// to isolate any further misbehavior.
#ifndef THORBRIDGE_NATIVES_LEVEL
#define THORBRIDGE_NATIVES_LEVEL 1  // 1=stats, 2=stats+weapon, 3=full HUD,
                                    // 4=3+weapon wheel (owned scan, LB),
                                    // 5=4+map blip census, GPS route
#endif

// Last episode value from a successful on_frame read. We echo it on
// every publish_waiting() so the HUD's episode-accent and the
// paused-view "episode" label don't collapse back to GTA IV defaults
// the moment the natives chain bypasses (pause, cutscene, etc.).
uint32_t g_last_episode = 0;

#if THORBRIDGE_CHEATS
// Latches whether the previous frame reached the "in_game=true" tail
// of on_frame. When it did and the current frame falls back to the
// waiting path (quit to menu, loading, save screen), the plugin's
// own world writes get rolled back once via cheats::reset_world() so
// the menu runs at normal speed and under game-controlled weather.
// The toggle mask is kept, so re-entering in-game re-applies it via
// cheats::tick() the moment the next on_frame reaches the end.
bool g_cheats_in_game_last = false;
#endif

void publish_waiting() {
    snapshot::Player p{};
    p.ts_ms = GetTickCount();
    // Even in the bypass path, mirror the user-pause flag so the HUD
    // can switch to a dedicated paused view regardless of whether the
    // natives chain fired this frame.
    bool u = false, s = false;
    natives::debug_read_pause(&u, &s);
    p.paused = u;
    p.episode = g_last_episode;
#if THORBRIDGE_CHEATS
    // Keep the mask in the published snapshot so the UI toggle lights
    // do not flicker off on pause / cutscene / loading. The underlying
    // game writes may well have been reset by the game itself in that
    // state; cheats::tick re-applies them the moment on_frame comes
    // back round to the end.
    p.cheats_mask = cheats::mask();
    // One-shot world reset on the in_game -> waiting edge. The natives
    // we call are safe no-ops while the pause gate is closed (invoke()
    // returns false silently), so wait for an invocable frame before
    // clearing the latch.
    if (g_cheats_in_game_last && natives::ok_to_invoke()) {
        cheats::reset_world();
        g_cheats_in_game_last = false;
    }
#endif
    snapshot::publish(p);
}

// Per-frame tick. Runs on the game's main thread AFTER the game's own
// CGameProcess completes.
void on_frame() {
#if THORBRIDGE_HOOK_NO_NATIVES
    // Isolation mode: hook fires but we touch nothing in the game.
    publish_waiting();
    return;
#endif

    // Even if ms_bScriptPause is wrong (round-3 showed it never
    // transitioned), ms_bUserPause is proven correct — honour it as the
    // primary user-pause gate.
    if (!natives::ok_to_invoke()) {
        publish_waiting();
        return;
    }

    natives::Context ctx;

    // GET_PLAYER_ID() — direct uint32_t return. First-touch: this is
    // where we discover whether the game's native table has been
    // populated. invoke() returns false if lookup returns null, which
    // happens pre-script-engine (main menu, mid-load before scripts
    // kick in). Skip the frame cleanly — no further natives fire.
    ctx.reset();
    if (!natives::invoke(natives::GET_PLAYER_ID, ctx)) {
        publish_waiting();
        return;
    }
    uint32_t player_id = ctx.get_u32();
    if (player_id == 0xFFFFFFFFu || player_id > 32) {
        publish_waiting();
        return;
    }

    // Stability: require k consecutive frames of the same small-positive
    // player id before we chain further natives. During save-load the
    // engine briefly returns values that look plausible but reference
    // half-constructed state — this is the window round-1 crashed in.
    static uint32_t s_last_player_id = 0xFFFFFFFFu;
    static unsigned s_stable_streak  = 0;
    if (player_id == s_last_player_id) {
        if (s_stable_streak < kStableFramesRequired) ++s_stable_streak;
    } else {
        s_last_player_id = player_id;
        s_stable_streak  = 0;
        publish_waiting();
        return;
    }
    if (s_stable_streak < kStableFramesRequired) {
        publish_waiting();
        return;
    }

    // GET_PLAYER_CHAR(player, &ped_handle) — ped written to out ptr.
    uint32_t ped_handle = 0;
    ctx.reset();
    ctx.push(player_id);
    ctx.push(&ped_handle);
    natives::invoke(natives::GET_PLAYER_CHAR, ctx);
    if (ped_handle == 0 || ped_handle == 0xFFFFFFFFu) {
        publish_waiting();
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
#if THORBRIDGE_NATIVES_LEVEL >= 2
    // Current weapon. GetCurrentCharWeapon returns a bool indicating
    // success; if the ped has no current weapon or the engine is in a
    // transient state, `out` may stay 0. We treat 0 as "unarmed" and
    // skip downstream ammo queries — passing weapon_id=0 to the ammo
    // natives has been observed to crash the game.
    uint32_t cur_weapon = 0;
    {
        ctx.reset(); ctx.push(ped_handle); ctx.push(&cur_weapon);
        natives::invoke(natives::GET_CURRENT_CHAR_WEAPON, ctx);
        p.weapon_id = static_cast<int32_t>(cur_weapon);
    }
    // Guard: valid IV weapon ids are small positive integers. Skip the
    // ammo chain for weapon=0 (fists/unarmed) and anything implausibly
    // large that would suggest a transient read.
    if (cur_weapon > 0 && cur_weapon < 200) {
        uint32_t out = 0;
        ctx.reset(); ctx.push(ped_handle);
        ctx.push(cur_weapon);
        ctx.push(&out);
        natives::invoke(natives::GET_AMMO_IN_CHAR_WEAPON, ctx);
        p.ammo_total = static_cast<int32_t>(out);

        out = 0;
        ctx.reset(); ctx.push(ped_handle);
        ctx.push(cur_weapon);
        ctx.push(&out);
        natives::invoke(natives::GET_AMMO_IN_CLIP, ctx);
        p.ammo_clip = static_cast<int32_t>(out);
    }
    // NOTE: the owned-weapons scan (GET_CHAR_WEAPON_IN_SLOT) is not
    // part of this level. Its first attempt here, across 13 slots,
    // crashed the game very shortly after being enabled. It now lives
    // in weapons.cpp behind level 4, bounded to the slots the ped
    // really has and run once a second instead of every frame.
#endif  // THORBRIDGE_NATIVES_LEVEL >= 2

#if THORBRIDGE_NATIVES_LEVEL >= 3
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
    // Current episode — 0=IV, 1=TLAD, 2=TBoGT. Direct uint32 return.
    // Used by the HUD to pick an accent palette per episode.
    {
        ctx.reset();
        natives::invoke(natives::GET_CURRENT_EPISODE, ctx);
        p.episode = ctx.get_u32();
        g_last_episode = p.episode;  // see publish_waiting()
    }
#endif  // THORBRIDGE_NATIVES_LEVEL >= 3

#if THORBRIDGE_NATIVES_LEVEL >= 4
    // Weapon wheel: is LB held, and what is the player carrying. The
    // wheel goes first so that pressing LB rescans in this same frame.
    // Both need in_vehicle from the level-3 block above.
    weapons::read_wheel(static_cast<uint32_t>(p.ts_ms), p);
    weapons::read_owned(ped_handle, static_cast<uint32_t>(p.ts_ms), p);
    // Dpad-down just-pressed: companion toggles the blips panel on
    // each edge. Independent of in_game / in_vehicle so it works on
    // the paused screen too.
    weapons::read_dpad_down(p);
#endif  // THORBRIDGE_NATIVES_LEVEL >= 4

#if THORBRIDGE_NATIVES_LEVEL >= 5
    // Map blips (census every 2 s) and the GPS route. New native
    // chains on the game thread are what crashed earlier rounds, so
    // this stays behind its own level until it has survived a device.
    if (blips::read(ped_handle, static_cast<uint32_t>(p.ts_ms), p)) {
        uint32_t n = 0;
        const snapshot::Blip* list = blips::list(&n);
        snapshot::publish_blips(list, n);
    }
#endif  // THORBRIDGE_NATIVES_LEVEL >= 5

#if THORBRIDGE_CHEATS
    // Re-apply active toggles when the player ped changed since the
    // last tick (hospital respawn, save-load, episode switch). Cheap
    // when the mask is empty, which it is until the player taps a
    // toggle. Snapshot gets the current mask so the companion lights
    // the right buttons regardless of whether a cheat ran this frame.
    cheats::tick(player_id, ped_handle,
                 static_cast<uint32_t>(p.ts_ms));
    p.cheats_mask = cheats::mask();
    g_cheats_in_game_last = true;

    // Paint section: palette-index pair for the car the player is in.
    // One extra GET_CAR_COLOURS call per in-vehicle frame; same class
    // of read as GET_CAR_SPEED above. Skipped on foot so the Paint
    // section greys out from vehicle_colours_valid=false.
    if (p.in_vehicle) {
        const uint32_t veh = game::get_car_char_is_using(ped_handle);
        if (veh != 0 && veh != 0xFFFFFFFFu) {
            uint32_t c1 = 0, c2 = 0;
            game::get_car_colours(veh, &c1, &c2);
            p.vehicle_colour1 = static_cast<int32_t>(c1);
            p.vehicle_colour2 = static_cast<int32_t>(c2);
            p.vehicle_colours_valid = true;
        }
    }
#endif

    snapshot::publish(p);

    // Phase 3: drain any client->server commands on the game thread
    // using the ped we just confirmed is live.
    commands::drain(ped_handle, p);
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
#if THORBRIDGE_HOOK_NO_NATIVES
    thor_log("[probes] enabled with per-frame hook — ISOLATION BUILD "
             "(on_frame does not invoke any natives)");
#else
    thor_log("[probes] enabled with per-frame hook — natives level %d, "
             "stability gate %u frames", THORBRIDGE_NATIVES_LEVEL,
             kStableFramesRequired);
#if THORBRIDGE_CHEATS
    thor_log("[cheats] enabled");
#endif
#endif
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
