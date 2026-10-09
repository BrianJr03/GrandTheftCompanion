# ThorHUD-GTAIV

AYN Thor bottom-screen companion for GTA IV. Live HUD on the Thor's 3.92″ 1240×1080 bottom screen while GTA IV plays on the main screen via [Game Native](https://github.com/utkarshdalal/GameNative).

Two artifacts:

- **`asi/`** — `ThorBridge.asi`, a 32-bit Windows DLL (C++ / IV-SDK) that loads into GTA IV via Ultimate ASI Loader inside Game Native's Wine prefix. Reads game state (money, health, armor, weapon, vehicle, radio) and publishes JSON over `127.0.0.1:47801` at 20 Hz.
- **`app/`** — Android companion (Jetpack Compose + Hilt + MVVM, min SDK 33). Foreground service tails the TCP stream; `Presentation` (or `setLaunchDisplayId` fallback) renders the HUD on the Thor's bottom display.

Full plan: `~/.claude/plans/purring-kindling-rainbow.md`.

## Current state

Milestones:
- [x] **M1** — ASI hello world builds
- [x] **M2** — Game-state reads: version detect + player health/armor via offset reads
- [x] **M3** — TCP bridge: ASI serves JSON over 127.0.0.1:47801
- [x] **M4** — Thor companion skeleton: Compose + Hilt + foreground service, TCP client with reconnect, debug APK builds
- [ ] M5 — Bottom-screen rendering via Presentation / fallback Activity
- [ ] M6 — Three MVP HUD panels
- [ ] M7 — First-run install wizard for the ASI
- [ ] M8 — Fingerprint Anker Games exe, pin offsets, verify end-to-end

## Prerequisites (macOS dev host)

```
brew install cmake mingw-w64        # cross-compiler for 32-bit Windows DLL
# Android Studio + Android SDK (API 33+)   — you already have this
```

## Building the ASI

```
cd asi
./build.sh      # produces build/ThorBridge.asi
```

## Building the Android app

```
./gradlew :app:assembleDebug    # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # install on a connected ADB device (Thor)
```

## Installing on Thor

1. Launch GTA IV at least once via Game Native so its Wine prefix exists.
2. Drop `ThorBridge.asi` + `dinput8.dll` (UAL) + (if Complete Edition) `CompleteEditionHook.asi` into the GTA IV install directory inside the Game Native prefix.
3. Install the ThorHUD Android app on the Thor. Launch Game Native → GTA IV. The HUD should appear on the bottom screen within ~2 seconds.

(The M7 first-run wizard will automate step 2.)
