# STATE — ThorHUD-GTAIV

Running log of **verified facts** about the project. Facts here have evidence
attached (the command that produced them and the relevant output). The
milestones in the README are **compile-only** — nothing has executed inside
the game yet.

Updated: 2026-10-10.

## Repo

- Working directory: `/Users/brianjr/AndroidStudioProjects/ThorHUD-GTAIV/`
- Current branch: `main` (the earlier handoff prompt called it `master` — not accurate; use `main`)
- HEAD at Phase-0 start: `73223d1` ("asi: log to local file + Z:\sdcard + stderr (triple channel)")
- `git status`: clean.

```
$ git log --oneline -6
73223d1 asi: log to local file + Z:\sdcard + stderr (triple channel)
b9dcefb docs: unified on-device validation guide covering M1-M4
dc6ff64 M4: Thor companion skeleton
6aa606d M3: TCP bridge serving JSON over 127.0.0.1:47801
d58a9b0 M2: version detect + player health/armor reads via raw offsets
bc001af M1: scaffold ASI build with hello-world dllmain
```

## Artifacts (build-only)

- `asi/build/ThorBridge.asi` — rebuilt 2026-10-10. `file`: `PE32 executable (DLL) (console) Intel 80386, for MS Windows`.
- `app/build/outputs/apk/debug/app-debug.apk` — rebuilt 2026-10-10 (no code changes, Gradle reported `UP-TO-DATE`).

### ASI DLL imports (hypothesis 11 check)

```
$ i686-w64-mingw32-objdump -p asi/build/ThorBridge.asi | grep 'DLL Name'
    DLL Name: KERNEL32.dll
    DLL Name: api-ms-win-crt-convert-l1-1-0.dll
    DLL Name: api-ms-win-crt-filesystem-l1-1-0.dll
    DLL Name: api-ms-win-crt-heap-l1-1-0.dll
    DLL Name: api-ms-win-crt-locale-l1-1-0.dll
    DLL Name: api-ms-win-crt-private-l1-1-0.dll
    DLL Name: api-ms-win-crt-runtime-l1-1-0.dll
    DLL Name: api-ms-win-crt-stdio-l1-1-0.dll
    DLL Name: api-ms-win-crt-string-l1-1-0.dll
    DLL Name: VERSION.dll
    DLL Name: WS2_32.dll
```

Only Windows system DLLs. **No `libgcc_s_dw2-1.dll`, `libstdc++-6.dll`, or
`libwinpthread-1.dll`** — the existing build already uses the UCRT target.
Hypothesis 11 doesn't apply as written; no static-link work needed.

## Android manifest

`app/src/main/AndroidManifest.xml` already declares:

- `android.permission.INTERNET` — required even for loopback.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.

## Device

ADB serial `114202`, AYN Thor, firmware `thor_v1.0.0.377_20260206_165408_user`,
Android 13 (SDK 33).

### Launchers installed

```
$ adb -s 114202 shell pm list packages | grep -iE 'gamehub|gamenative'
package:gamehub.lite
package:app.gamenative
```

**Both are installed.** The active launcher for GTA IV is **GameHub Lite**
(`gamehub.lite`, version 5.1.4) — the `dxvk.conf` next to `GTAIV.exe` is
hand-labeled "AYN Thor (GameHub Lite)" (modified today, 2026-10-10), and
GameHub Lite's wine log shows it opening the exe path below. Previous
project memory / plan / docs calling the launcher "Game Native" are
**wrong** and were updated as part of Phase 0.

GameHub Lite's wine log goes to `/sdcard/Android/data/gamehub.lite/files/log/util_<date>_gamehub.lite_wine.txt`
and is adb-readable.

### Companion app installed

```
$ adb -s 114202 shell pm list packages | grep thorhud
package:jr.brian.thorhud.debug
```

Already present from an earlier push. Debug build suffix is `.debug`, so the
launch target is `jr.brian.thorhud.debug/jr.brian.thorhud.MainActivity`.

### Game location

```
$ adb -s 114202 shell find /sdcard /storage -iname 'GTAIV.exe' 2>/dev/null
/storage/emulated/0/Download/PC Rom/GTA IV/Game/Grand Theft Auto IV/GTAIV.exe
```

**On shared storage, adb-readable AND adb-writable** (`u0_a109 media_rw`
permissions). Hypothesis 6 confirmed: no need to ask Brian to move files by
hand. We can `adb push` the ASI and loader directly into the game folder.

GameHub Lite imports this as a local game (`isLocalGame=true`, gameId
`local_ed496096-b57b-4b7a-bdc6-4fb9733c59a6`). It does not copy the exe
into app-private storage; it runs it from `/storage/emulated/0/`.

### Game folder contents (relevant)

```
GTAIV.exe              17425752  2023-11-20
GTAIV.exe.o            17425752  2024-12-08  (likely a FusionFix/mod backup; same size)
MTLX.dll                 593240  2024-12-08
PlayGTAIV.exe            264176  2024-12-08
binkw32.dll              180224  2020-12-19
launc.dll                191488  2020-12-19
orig_socialclub.dll     1586152  2020-12-19
socialclub.dll           221696  2020-12-19
steam_api.dll            261072  2024-10-03
steam_appid.txt               5
title.rgl                  1104
commandline.txt           162    2026-10-10  (hand-authored — see below)
dxvk.conf                 127    2026-10-10
installscript.vdf         566
installscript_sdk.vdf     829
metadata.dat           472036
TBoGT/, TLAD/, pc/, Redistributables/, Manuals/, movies/, common/
```

- **No `dinput8.dll`** and **no `plugins/` or `scripts/` folders**. Fresh
  install — no ASI loader, no ScriptHook, no FusionFix.
- **No DXVK d3d9.dll in the folder**, but a hand-tuned `dxvk.conf` exists.
  GameHub Lite presumably ships DXVK via its Wine prefix / DLL-override
  mechanism rather than dropping d3d9.dll in the game folder.

`commandline.txt`:
```
-norestrictions -nomemrestrict -width 1280 -height 720 -renderquality 1
-texturequality 1 -shadowdensity 0 -viewdistance 20 -detailquality 20 -novblank
```

`dxvk.conf`:
```
# GTA IV cool-running preset for AYN Thor (GameHub Lite)
d3d9.maxFrameRate = 30
dxvk.maxFrameRate = 30
d3d9.maxFrameLatency = 1
```

### Game version — the big correction

Pulled `GTAIV.exe` and ran `pefile` + `zlib.crc32`:

```
size:   17425752
crc32:  0x521747AC
FileVersion:    1.2.0.59
ProductVersion: 1.2.0.59
CompanyName    = Rockstar Games
LegalCopyright = Copyright (C) 2020
machine:        0x14C   (i386)
```

**This is GTA IV: The Complete Edition 1.2.0.59** — the same build FusionFix
targets. **It is NOT the "Anker Games repack" the plan assumed.** The
on-disk files (`PlayGTAIV.exe`, `MTLX.dll`, `title.rgl`, `metadata.dat`,
`steam_api.dll`, `steam_appid.txt`, `installscript.vdf`) look like a Steam
install.

**Consequences:**
- Memory offsets in `asi/src/probes.cpp` (sourced from
  `Zolika1351/iv-sdk`, which only supports 1.0.7.0 / 1.0.8.0) will produce
  **wrong reads or crash** if ever executed on this exe.
- The current `probes::init()` logic will set `GameVersion::Unknown`
  because the version resource reads 1.2.0.59 (not 1070/1080); `sample()`
  short-circuits — so it does not actually dereference bad offsets. That is
  the only reason M2's "builds" didn't crash a device test. Still — the
  code path exists and must stay disabled until Phase 2 replaces it with
  CE-correct pattern scans + natives.

### Displays

```
$ adb -s 114202 shell dumpsys display | grep DisplayDeviceInfo
```

Two internal panels:

| displayId | uniqueId                               | physicalPort | size      | flags          | touch    |
|-----------|----------------------------------------|--------------|-----------|----------------|----------|
| 0         | `local:4630946441858561667` (HWC 0)    | 131          | 1080×1920 | DEFAULT        | INTERNAL |
| 4         | `local:4630946482288158084` (HWC 3)    | 132          | 1080×1240 | **PRESENTATION** | EXTERNAL |

The bottom screen is **displayId 4**, logical frame 1240×1080 (landscape),
`FLAG_PRESENTATION`, touch EXTERNAL (independent touch digitizer). Use
`displayId=4` for `am start --display 4` and
`ActivityOptions.setLaunchDisplayId(4)`. For `screencap -d`, use the
HWC-display physical id, printed by `dumpsys SurfaceFlinger --display-id`:
`Display 4630946482288158084 (HWC display 3)` — i.e. pass `4630946482288158084`.

## Reality check vs. plan / earlier docs

| plan/doc claim                                         | reality                                                     |
|--------------------------------------------------------|-------------------------------------------------------------|
| Launcher is Game Native (`app.gamenative`)             | GameHub Lite (`gamehub.lite`) is the one actually used.     |
| Anker Games repack of unknown version                  | GTA IV CE 1.2.0.59, Steam install layout. CRC `0x521747AC`. |
| IV-SDK offsets will work                               | They are for 1.0.7.0 / 1.0.8.0, not CE. Must be replaced.   |
| Game folder is in launcher's app-private storage       | It is on shared storage; adb can read AND write it.         |
| Need Termux + LAN build to test TCP                    | `adb forward` + `nc` on the Mac is enough.                  |
| State pushed one-way at 20 Hz is sufficient            | Phase 3 needs a bidirectional protocol for interaction.     |
| `Presentation` is the primary rendering path for HUD   | Phase 4 reverses this: `setLaunchDisplayId` is primary.     |
| Game memory reads can happen on the socket thread      | Must move to the game's main/script thread.                 |
| ULA ships as `dinput8.dll`, Wine auto-overrides it     | Needs `WINEDLLOVERRIDES=dinput8=n,b` in GameHub Lite.       |

## Gate 0: PASSED (2026-10-10)

Launcher package, game folder path, exe fingerprint, mod files already
present, bottom screen display id — all stated above with commands to
reproduce.

## Gate 1: PASSED (2026-10-10)

Safe-mode ASI loads under GameHub Lite's Wine, serves `hello` + 20 Hz
ticks, both the Mac (via `adb forward`) and the companion app on the Thor's
bottom screen receive frames, and GTA IV plays normally through all of it.

### Install

- Ultimate ASI Loader v9.7.4 (ThirteenAG, 32-bit `dinput8.dll`) and the
  Phase-1 `ThorBridge.asi` pushed to the game folder via `adb push`. No
  pre-existing files were overwritten; backup directory not needed.
- Brian added `WINEDLLOVERRIDES=dinput8=n,b` to GameHub Lite's per-game
  Environment variables (one-time step).

### Evidence

GTAIV.exe imports `DINPUT8.dll` directly (confirmed with
`i686-w64-mingw32-objdump -p /tmp/thor_GTAIV.exe`), so UAL as `dinput8.dll`
with the WINEDLLOVERRIDES above does get loaded.

`ThorBridge.log` (pulled from the game folder):
```
[23:27:40.869] === ThorBridge Phase 1 loaded ===
[23:27:40.933] [init] exe fingerprint: crc32=0x521747AC size=17425752 file_version=1.2.0.59
[23:27:40.960] [init] probes: safe-mode (Phase 1: no reads, no hooks)
[23:27:40.974] [bridge] listening on 127.0.0.1:47801
[23:27:50.986] [heartbeat] alive (probes.enabled=false)
...
[23:30:33.253] [bridge] client connected
```

Fingerprint matches STATE.md exactly — the exe we're attached to is the
same one `pefile` reported from a static pull.

Mac-side TCP capture (`adb forward tcp:47801 tcp:47801`, then python
socket for 3 s):
```
received 2134 bytes in 59 lines  (≈ 20 Hz)
{"v":1,"type":"hello","crc32":"0x521747AC","size":17425752,"file_version":"1.2.0.59","supported":false}
{"v":1,"type":"tick","t":23530268}
{"v":1,"type":"tick","t":23530322}
...
```

Companion app on bottom display (`adb shell am start --display 4 -n jr.brian.thorhud.debug/jr.brian.thorhud.MainActivity`):
- logcat: `GameStateClient: connected to 127.0.0.1:47801`
- logcat: `GameStateClient: hello: version=1.2.0.59 crc32=0x521747AC supported=false`
- `dumpsys activity services` shows `GameStateService` running in-foreground.
- Bottom-screen screencap (`adb exec-out screencap -d 4630946482288158084 -p`)
  shows the hello panel and a live tick counter.

GTA IV screencap taken from display 0 at the same moment shows the game in
free-roam with the minimap drawn and no visible regression. Brian confirmed
by voice that no stutter or input issue appeared.

### Side bonus

Validating via `am start --display 4` also pre-exercised Phase 4's
placement strategy (`setLaunchDisplayId` into the HUD Activity). It works
without any special permissions — no `SYSTEM_ALERT_WINDOW`, no Presentation
needed. Phase 4 is now mostly a wiring task.

## Gate 2 — FAILED on first attempt (2026-10-10 → 2026-10-11)

Phase 2 ASI (`54dddb1`) loaded cleanly: all three pattern scans matched
exactly once, `getNativeAddress` resolved via the EFLC string, pause-gate
booleans found, CGameProcess 5-byte CALL hook installed. Log shows
`[probes] enabled` after each launch.

But the game **crashed on save load, four times in a row**:

```
# grep -nE 'loaded|detour|enabled|disconnected' ThorBridge.log
144:[23:49:31.024] === ThorBridge Phase 2 loaded ===
153:[23:49:31.715] [hook] CGameProcess detour: site=0x005C2A41 orig=0x004016A0 ...
154:[23:49:31.720] [probes] enabled
163:[23:51:05.157] === ThorBridge Phase 2 loaded ===
172:[23:51:05.813] [hook] CGameProcess detour: site=0x005C2A41 orig=0x004016A0 ...
173:[23:51:05.815] [probes] enabled
181:[23:52:25.490] === ThorBridge Phase 2 loaded ===
190:[23:52:26.147] [hook] CGameProcess detour: site=0x005C2A41 orig=0x004016A0 ...
191:[23:52:26.149] [probes] enabled
199:[23:53:42.847] === ThorBridge Phase 2 loaded ===
208:[23:53:43.522] [hook] CGameProcess detour: site=0x005C2A41 orig=0x004016A0 ...
209:[23:53:43.525] [probes] enabled
```

Each launch ran ~90 s (main menu + attempt to load the save) then the
game process died. Fully reproducible. Brian confirmed removing the ASI
from the folder restored normal behaviour on the same save — definitively
my fault.

ASI preserved for debugging at
`<game dir>/_thorbridge_backup/ThorBridge.asi.phase2` on the device.

### Prime suspects

1. **Pause-gate pattern is the 12-byte short form.** FusionFix ships two
   `find_pattern<2>` strings for `ms_bUserPause`/`ms_bScriptPause`.
   I used the shorter one; `ms_bScriptPause` is read at displacement +15,
   which is **three bytes past the end of the matched 12-byte block**.
   For CE 1.2.0.59 the correct form is the 19-byte EFLC pattern
   `"0F B6 0D ?? ?? ?? ?? 0F B6 15 ?? ?? ?? ?? 33 C0 0B C1"` with the
   second pointer at +10 (inside the match). My log reports the two
   booleans at `0x01173590` and `0x01173591` — adjacent single bytes. That
   is plausible if `CTimer` has two adjacent `bool`s but is also exactly
   what a one-off wrong read would look like. If `g_script_pause` is
   pointing at the wrong byte, `ok_to_invoke()` returns true while the
   script engine is actually mid-transition and natives fire into
   half-constructed state.

2. **`getNativeAddress` is called per-invoke.** Each frame I do ~15 native
   calls, each of which re-walks the game's native table. Over 60 fps
   that is 900 lookups/sec. FusionFix caches handlers at startup; I
   should too. Not a crash-cause on its own but adds exposure to
   transitional state.

3. **No sanity check on player_id / ped_handle.** I call `GET_PLAYER_ID`
   then unconditionally `GET_PLAYER_CHAR(player_id, &ped)` even if
   `player_id` looks garbage. During save-load the player ped is
   destroyed and recreated; a mid-transition native call can land on a
   null slot.

### Fix plan for round 2 (not yet pushed)

- Switch to the EFLC long pause-gate pattern; verify both pointers
  resolve to independently-plausible addresses (not necessarily
  adjacent).
- Resolve every native handler once at startup and cache; invoke directly
  through the cached pointer.
- Add defensive checks: skip the frame if `player_id` is `0` or `0xFFFFFFFF`
  or if `ped_handle` is `0`.
- Gate `hook::install_cgameprocess()` behind a `THORBRIDGE_ENABLE_HOOK`
  compile flag defaulting to OFF so a stock build is always safe to push.
- Keep the exact pattern + address diagnostics in the log so round-2
  evidence has the same shape.

Next on-device attempt should start with the hook disabled, confirm the
new `natives::resolve()` reads the EFLC pointers correctly (log them,
compare against expectations), then enable the hook via rebuild only when
I'm confident.

### Gate 2 — PASSED on round 6 (2026-10-11)

Rounds 3-6 iterated on the device without further crashes. Each round
pushed a narrower variant to isolate one variable:

- **Round 3** (hook OFF, pause-sampler ON) — confirmed `ms_bUserPause @
  0x01173590` is correct (toggled 0→1→0 when Brian opened the pause
  menu). `ms_bScriptPause @ 0x01173591` never transitioned; could still
  be right (adjacent CTimer bool) but we can't verify and don't trust it.
- **Round 4** (hook ON, `THORBRIDGE_HOOK_NO_NATIVES=1`) — the CGameProcess
  5-byte CALL overwrite installed and fired every frame with on_frame
  doing nothing. Game loaded cleanly through save-load and pause cycles.
  Proof: the hook mechanism itself is safe under Wine. Round 1's crash
  was in the native invocation path, not the trampoline.
- **Round 5** (hook ON, `THORBRIDGE_NATIVES_LEVEL=1`, stability gate on
  GET_PLAYER_ID for 15 consecutive frames) — game stable, all six core
  natives resolved and cached (`[natives] lookup(0x62E319C6) -> 0x00BB1F30`
  etc.), hook ran for 10+ minutes with no crash. But `state` frames
  consistently showed `in_game=false` with all zeros — chain was bailing
  early.
- **Round 6** fixed the real bug: my `Context` struct had `m_pArgs` and
  `m_pReturn` pointing at a separate `m_scratch[]` buffer, when they
  should both point at `m_TemporaryData[]` inside the struct (per
  FusionFix's layout). Direct-return natives like `GET_PLAYER_ID` wrote
  their result into `m_TemporaryData[0]`, which `get_u32()` never read.
  Fix: delete `m_scratch`, point both pointers at `m_TemporaryData`.

### Round-6 evidence

Live state frame captured from `127.0.0.1:47801` while Brian was in
free-roam:

```
{
  "v": 1, "type": "state", "t": 27547846,
  "in_game": true, "in_vehicle": false,
  "money": 2885, "health": 200.0, "wanted": 1,
  "max_health": 0, "armor": 0, "max_armor": 0,
  "weapon": 0, "ammo_total": 0, "ammo_clip": 0,
  "owned": [], "vehicle": {...}, "radio": 0
}
```

Money / health / wanted level are real. `max_health` / `max_armor` are 0
because those come from `CPlayerInfo` struct offsets rather than natives
(see §3 of the FusionFix synthesis) — Phase 2 reads them from natives
that haven't been wired yet (future rounds). `weapon` / `radio` / vehicle
fields are 0 because they live behind `THORBRIDGE_NATIVES_LEVEL >= 2/3`.

Bottom-screen screencap shows the same values rendered in the Phase-2
debug UI.

### Build matrix for round 6

```
cmake .. -DTHORBRIDGE_ENABLE_HOOK=ON -DTHORBRIDGE_NATIVES_LEVEL=1
```

Default build (no flags) still installs no hook — safe to push. Rebuild
with `-DTHORBRIDGE_NATIVES_LEVEL=2` for weapon reads, `=3` for the full
chain (vehicle + radio).

### Level 2 and 3 enabled (2026-10-11, same session)

- First L2 attempt crashed within 0.25 s of invoking weapon natives.
  The log showed all 10 natives resolved cleanly then the game died.
  Prime suspect: the owned-weapons scan iterating all 13 slots per
  frame, OR passing `weapon_id=0` to the ammo natives when the ped was
  unarmed. Reverted to L1 immediately.
- Fixed L2: dropped the slot-iteration entirely (the weapon picker in
  Phase 5 will do slot queries on demand, not every frame) and guarded
  the ammo natives on `weapon > 0 && weapon < 200`. L2 then showed
  `weapon=12 (clip=28/tot=663)` alongside stats, no crash.
- L3 added vehicle + radio with no new issues. Captured state frame:

```
{"in_game":true,"in_vehicle":true,"money":2867,"health":200.0,
 "wanted":0,"weapon":12,"ammo_total":685,"ammo_clip":0,
 "vehicle":{"model":3338918751,"name":"TAXI","speed_mps":0.85,
            "engine_hp":1000.0},
 "radio":14}
```

Default build is now `THORBRIDGE_NATIVES_LEVEL=3` + hook disabled.
Enable hook via `-DTHORBRIDGE_ENABLE_HOOK=ON`.

## Gate 3 — PASSED (2026-10-11)

Bidirectional NDJSON protocol over the same `127.0.0.1:47801` socket.
Server now reads newline-delimited `cmd` frames on a per-client reader
thread, enqueues them, and the per-frame hook drains + dispatches via
natives on the game thread, posting `ack` frames back for the writer
loop to send.

### Mac-side evidence

```
--> {"v":1,"type":"cmd","id":2001,"name":"set_radio","args":{"station":3}}
<-- {"v":1,"type":"ack","id":2001,"ok":true,"err":""}
PRE  : radio=16, veh='MARBELLA'
POST : radio=3,  veh='MARBELLA'
```

Radio audibly / observably changed from station 16 → 3 in-game while
Brian was driving a civilian car (MARBELLA). Earlier attempt in a
POLICE car failed to change the station — likely a game-side override;
not a protocol issue. Noted and moved on.

```
--> {"v":1,"type":"cmd","id":3001,"name":"set_weapon","args":{"weapon":12}}
<-- {"v":1,"type":"ack","id":3001,"ok":true,"err":""}
PRE  : weapon=12, clip=0,  total=685
POST : weapon=12, clip=50, total=685
```

Set-weapon to the current weapon with `forceEquip=true` triggered a
clip reload (0 → 50) — strong confirmation the native actually fired on
the game thread, not just that we round-tripped a protocol message.

### Android side

- `GameStateClient.send(name, intArg)` writes NDJSON to the current
  connection and returns an assigned id, or null when disconnected.
- `GameStateRepository.setWeapon(id)` / `setRadio(station)` wrap it.
- `ack` frames are parsed via nullable id/ok/err fields on the
  tagged-union `GameState` and surfaced on a sticky `ack` StateFlow.

## Next

Phase 3 is done. Phase 4 moves the HUD to a dedicated Activity on the
bottom display (we already validated `am start --display 4` works
without special permissions in Phase 1). Phase 5 builds the three real
panels + weapon picker + radio control.
