**GTC** (Grand Theft Companion) — AYN Thor bottom-screen companion for GTA IV. Live HUD on the Thor's bottom screen while GTA IV plays on the main screen via [GameHub Lite](https://www.gamehublite.com/) (`gamehub.lite`) or any Winlator-family launcher.

Repo contains two artefacts: the **GTC** Android companion app and **ThorBridge.asi**, the ASI plugin that reads game state from inside the GTA IV process.

## Downloads

Prebuilt artefacts (debug APK + ASI) are attached to each [GitHub Release](https://github.com/BrianJr03/GrandTheftCompanion/releases/latest). The latest `main` build is always available as a workflow artefact under [Actions → build](https://github.com/BrianJr03/GrandTheftCompanion/actions/workflows/build.yml) (requires being signed into GitHub).

Two artifacts:

- **`asi/`** — `ThorBridge.asi`, a 32-bit Windows DLL (C++) that loads into GTA IV via Ultimate ASI Loader inside GameHub Lite's Wine prefix. Reads game state (money, health, armor, weapon, vehicle, radio) and publishes JSON over `127.0.0.1:47801` at 20 Hz.
- **`app/`** — **GTC** (Grand Theft Companion), the Android app (Jetpack Compose + Hilt + MVVM, min SDK 33). A dedicated HUD Activity launched on the bottom display renders the HUD while GTA IV plays on the main screen.

Licensed under **GPL-3.0-or-later** — see [LICENSE](LICENSE) and [LEGAL.md](LEGAL.md). Rockstar's **GTA IV is not included and must not be redistributed with this project**; bring your own legitimately-purchased copy of Complete Edition 1.2.0.59.

## Roadmap

- **Weapon picker** 

- **Broader device coverage** — only tested on the AYN Thor with
  GameHub Lite. The code paths are generic (secondary-display
  detection enumerates `FLAG_PRESENTATION` displays rather than
  hardcoding `displayId=4`; the ASI only needs any Winlator-family
  launcher that honours `WINEDLLOVERRIDES`), but ScriptHook /
  Odin / RG556 / RP5 / etc. are all unverified. Each new device
  needs: display enumeration confirmed, bottom-screen touch
  behaviour confirmed, ASI load path confirmed.

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
adb push asi/build/ThorBridge.asi "$GAME_DIR/"
adb push /path/to/dinput8.dll "$GAME_DIR/"    # Ultimate ASI Loader (ThirteenAG), 32-bit
```

In your launcher, open the game's settings → Environment variables and add:
`WINEDLLOVERRIDES = dinput8=n,b` (native then builtin — forces Wine to load UAL instead of its own `dinput8`). Then launch GTA IV as normal.
