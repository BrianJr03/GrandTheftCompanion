# On-device validation (M1–M4) on the AYN Thor

End-to-end verification pass. By the end, you'll know whether localhost TCP crosses the Game Native sandbox (the single biggest project risk) and whether ThorBridge can read your Anker Games build's memory correctly.

## 0. Prep

On the Thor:
- **Enable Developer options**: Settings → About → tap Build number 7×.
- **Enable USB debugging**: Developer options → USB debugging.
- Install **Termux** from F-Droid: https://f-droid.org/en/packages/com.termux/
  - (The Play Store Termux is deprecated and won't update packages.)
- In Termux: `pkg update && pkg install netcat-openbsd`
- Confirm GTA IV launches to the main menu via Game Native.

On the Mac, confirm the Thor shows up over ADB:
```
adb devices
```
Should list the Thor's serial. If it says `unauthorized`, accept the fingerprint prompt on the Thor.

## 1. Deploy the ASI → validates M1

Locate GTA IV's install dir inside Game Native's prefix. In Game Native, long-press GTA IV → **Open folder** (or **Install folder** / **Game folder** — exact label varies by version). Note the path. Common shape:
```
Android/data/com.winlator.gamenative/.../wine_prefix/drive_c/.../GTA IV/
```

Push the ASI from your Mac to the Thor:
```
adb push ~/AndroidStudioProjects/ThorHUD-GTAIV/asi/build/ThorBridge.asi /sdcard/Download/
```

Then on the Thor, move `/sdcard/Download/ThorBridge.asi` into the GTA IV folder using Game Native's file manager (or Material Files / any Android file manager that can reach that path).

While you're there, confirm **`dinput8.dll`** (Ultimate ASI Loader) is also in that folder next to `GTAIV.exe`. If not, grab UAL v1.84+ from https://github.com/ThirteenAG/Ultimate-ASI-Loader/releases/latest (download the 32-bit `dinput8.dll`) and copy it in the same way.

**Verify M1:** Launch GTA IV via Game Native. Play through to main menu, then quit. Pull the log:
```
adb pull /sdcard/Android/.../GTA\ IV/ThorBridge.log /tmp/thor.log && cat /tmp/thor.log
```
(Replace the `...` with your real prefix path.)

Expected first two lines:
```
[HH:MM:SS.mmm] === ThorBridge M3 loaded ===
[HH:MM:SS.mmm] [init] game version: <classic 1.0.8.0 | unknown>
```

If the file doesn't exist, the ASI didn't load — see M1 doc for troubleshooting (DLL overrides, UAL version).

## 2. Verify game-state reads → M2

Launch a saved game (or start a new one), play for a few seconds in-game. Quit. Pull the log again and look for:
```
[init] exe fingerprint: crc32=0x???????? size=??????? label=unknown
[heartbeat] health=200.0 max_health=200 max_armor=0
```

**Record the `crc32=` and `size=` values.** M8 pins these into our version table so the Anker Games build gets a real `label`.

Interpretations of the `game version:` line:
- **`classic 1.0.8.0`** or **`classic 1.0.7.0`** — great, our offsets are correct, you should see sensible health values.
- **`unknown`** — the version resource says something we don't recognize (likely Complete Edition 1.2.x or a stripped repack). `probes::sample()` short-circuits, so `[heartbeat]` lines will say `no player (title/loading)` forever. Not a failure — expected until M8.

If health reads back as something crazy (NaN, 1e-38, 10000+): wrong offsets for your build. Still useful info; note it and we fix in M8.

## 3. Verify TCP crosses the sandbox → M3 (the big one)

Keep GTA IV running (or relaunch it). In Termux on the Thor:
```
nc 127.0.0.1 47801
```

**Expected:** steady stream of lines at 20 Hz, pre-game:
```
{"t":12345,"valid":false}
{"t":12395,"valid":false}
```
In-game (if version detected as 1.0.7.0/1.0.8.0):
```
{"t":45678,"valid":true,"health":200.0,"max_health":200,"max_armor":0}
```

**If this works, the entire architecture is validated.** Everything downstream (M4 app → M5 Presentation → M6 panels) is just UI on top of a working stream.

**If `nc` says `Connection refused`:**
- Check `ThorBridge.log` for a `[bridge] listening on 127.0.0.1:47801` line. If missing, the server never started — look for a `[bridge]` error.
- If `listening` is there but `nc` still refuses: this is the pivot moment. Likely SELinux policy blocking cross-sandbox loopback on Android 13. We'd need to fall back to a shared file in Game Native's exposed storage (bigger install UX, but doable). Flag it and we regroup.

Ctrl+C out of `nc` when you've seen the stream.

## 4. Launch the Thor companion → M4

```
adb install ~/AndroidStudioProjects/ThorHUD-GTAIV/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n jr.brian.thorhud.debug/jr.brian.thorhud.MainActivity
```

Grant the notifications permission when it prompts. You should see:
- A persistent notification "ThorHUD running" in the status bar
- The primary-screen UI showing `valid = false` while GTA IV is on main menu
- Numbers updating once you're in a loaded save

If the UI stays at `valid = false` forever while `nc` sees valid data, the Android app has a connection problem — check `adb logcat | grep GameStateClient`.

## 5. Done when

- M1: `ThorBridge.log` has a `[ThorBridge M3 loaded]` line
- M2: fingerprint recorded; sensible `health=` values appear when in a loaded save
- M3: `nc 127.0.0.1 47801` sees newline-delimited JSON at 20 Hz
- M4: ThorHUD on the primary screen shows live state tracking GTA IV

Report back with:
1. The `crc32=` and `size=` from the log
2. Which `game version:` was detected
3. Whether `nc` saw the stream (yes / connection refused / something else)
4. Whether ThorHUD showed live values

That's everything I need to decide the right M5 shape.
