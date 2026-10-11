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

## Current gate

**Gate 0 PASSED.** Launcher package, game folder path, exe fingerprint,
mod files already present, bottom screen display id — all stated above with
commands to reproduce.

Next: **Phase 1** — safe-mode ASI (no memory reads, no hooks on unrecognised
exe) + end-to-end pipe test with `adb forward` and `nc`.
