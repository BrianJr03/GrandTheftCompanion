# ThorHUD-GTAIV

AYN Thor bottom-screen companion for GTA IV. Live HUD on the Thor's 3.92″ 1240×1080 bottom screen while GTA IV plays on the main screen via [GameHub Lite](https://www.gamehublite.com/) (`gamehub.lite`), a Winlator-family launcher.

Two artifacts:

- **`asi/`** — `ThorBridge.asi`, a 32-bit Windows DLL (C++) that loads into GTA IV via Ultimate ASI Loader inside GameHub Lite's Wine prefix. Reads game state (money, health, armor, weapon, vehicle, radio) and publishes JSON over `127.0.0.1:47801` at 20 Hz.
- **`app/`** — Android companion (Jetpack Compose + Hilt + MVVM, min SDK 33). A dedicated HUD Activity launched on the bottom display renders the HUD while GTA IV plays on the main screen.

Full plan: `~/.claude/plans/purring-kindling-rainbow.md`. Verified-facts log: [docs/STATE.md](docs/STATE.md).

## Current state

Milestones — all pre-Phase-0 milestones only mean "code compiled," **not** "ran inside the game":

- [x] **M1** — ASI hello-world: *builds*, unverified on device
- [x] **M2** — Game-state reads via raw offsets: *builds*, unverified on device. **Offsets are for GTA IV 1.0.7.0/1.0.8.0; actual game is Complete Edition 1.2.0.59. The offset path must stay disabled on CE until Phase 2 replaces it with pattern scans + natives.**
- [x] **M3** — TCP bridge: *builds*, unverified on device
- [x] **M4** — Thor companion skeleton (Compose + Hilt + foreground service): *builds*, unverified on device
- [ ] Phase 1 — Prove the pipe end-to-end (safe-mode ASI, no memory reads)
- [ ] Phase 2 — CE-correct game-state reads on the game thread
- [ ] Phase 3 — Two-way protocol with commands and acks
- [ ] Phase 4 — Bottom-screen HudActivity via `setLaunchDisplayId`
- [ ] Phase 5 — HUD panels and touch interaction

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

Current (Phase-1) install flow — the game folder is on shared storage, so adb pushes directly:

```
GAME_DIR='/storage/emulated/0/Download/PC Rom/GTA IV/Game/Grand Theft Auto IV'
adb -s 114202 push asi/build/ThorBridge.asi "$GAME_DIR/"
adb -s 114202 push /path/to/dinput8.dll "$GAME_DIR/"    # Ultimate ASI Loader (ThirteenAG), 32-bit
```

In GameHub Lite, open the game's `...` menu → settings → Environment variables:
`WINEDLLOVERRIDES = dinput8=n,b` (native then builtin — forces Wine to load UAL instead of its own `dinput8`). Then launch GTA IV from GameHub Lite as normal.
