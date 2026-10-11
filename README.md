# ThorHUD-GTAIV

**GTC** (Grand Theft Companion) — AYN Thor bottom-screen companion for GTA IV. Live HUD on the Thor's 3.92″ 1240×1080 bottom screen while GTA IV plays on the main screen via [GameHub Lite](https://www.gamehublite.com/) (`gamehub.lite`) or any Winlator-family launcher.

Repo contains two artefacts: the **GTC** Android companion app and **ThorBridge.asi**, the ASI plugin that reads game state from inside the GTA IV process.

## Downloads

Prebuilt artefacts (debug APK + ASI) are attached to each [GitHub Release](https://github.com/BrianJr03/GrandTheftCompanion/releases/latest). The latest `main` build is always available as a workflow artefact under [Actions → build](https://github.com/BrianJr03/GrandTheftCompanion/actions/workflows/build.yml) (requires being signed into GitHub).

To cut a release, tag the commit and push:

```
git tag v0.1.0
git push origin v0.1.0
```

CI builds both artefacts and attaches them to a new Release automatically.

Two artifacts:

- **`asi/`** — `ThorBridge.asi`, a 32-bit Windows DLL (C++) that loads into GTA IV via Ultimate ASI Loader inside GameHub Lite's Wine prefix. Reads game state (money, health, armor, weapon, vehicle, radio) and publishes JSON over `127.0.0.1:47801` at 20 Hz.
- **`app/`** — **GTC** (Grand Theft Companion), the Android app (Jetpack Compose + Hilt + MVVM, min SDK 33). A dedicated HUD Activity launched on the bottom display renders the HUD while GTA IV plays on the main screen.

Full plan: `~/.claude/plans/purring-kindling-rainbow.md`. Verified-facts log: [docs/STATE.md](docs/STATE.md). Licensing and legal posture: [LEGAL.md](LEGAL.md).

Licensed under **GPL-3.0-or-later** — see [LICENSE](LICENSE). Rockstar's **GTA IV is not included and must not be redistributed with this project**; bring your own legitimately-purchased copy of Complete Edition 1.2.0.59.

## Current state

All five phases of the handoff plan are complete on-device. See
[docs/STATE.md](docs/STATE.md) for per-gate evidence (logs, state
frames, screencaps).

- [x] **Phase 0** — Ground truth: launcher, exe fingerprint, display IDs
- [x] **Phase 1** — Pipe: safe-mode ASI + 20 Hz hello/tick → companion
- [x] **Phase 2** — CE natives on game thread → live state frames
- [x] **Phase 3** — Two-way protocol with cmd/ack
- [x] **Phase 4** — HudActivity on bottom display via `setLaunchDisplayId`
- [x] **Phase 5** — Three HUD panels + radio picker + OLED theme

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

## Building the Android app (GTC)

```
./gradlew :app:assembleDebug    # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # install on a connected ADB device (Thor)
```

## Installing on Thor

**Launcher**: install **GameHub Lite**, **Winlator** (or a fork such as **Winlator Cmod**), or **GameNative** — any launcher that lets you set per-game environment variables, needed for `WINEDLLOVERRIDES`.

The game folder is on shared storage, so adb pushes directly:

```
GAME_DIR='/storage/emulated/0/Download/PC Rom/GTA IV/Game/Grand Theft Auto IV'
adb -s 114202 push asi/build/ThorBridge.asi "$GAME_DIR/"
adb -s 114202 push /path/to/dinput8.dll "$GAME_DIR/"    # Ultimate ASI Loader (ThirteenAG), 32-bit
```

In your launcher, open the game's settings → Environment variables and add:
`WINEDLLOVERRIDES = dinput8=n,b` (native then builtin — forces Wine to load UAL instead of its own `dinput8`). Then launch GTA IV as normal.
