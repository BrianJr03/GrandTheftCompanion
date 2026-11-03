# Legal notes

## License

Grand Theft Companion (GTC) — both the Android companion (`app/`) and
the ASI plugin (`asi/`) — is released under the **GNU General Public
License v3.0 or later**. See [`LICENSE`](LICENSE) for the full text.

Copyright © 2026 Brian Jr. (BrianJr03).

## What this project does NOT ship

GTC contains **no Rockstar Games binaries or media**. No
`GTAIV.exe` / `GTA5.exe`, no save files, no textures, models, audio,
video or game scripts. You need your own legitimately-purchased copy
of **Grand Theft Auto IV: The Complete Edition 1.2.0.59** (or **Grand
Theft Auto V**, for the experimental `asi-v/` plugin) to use the
project.

GTC **may include short in-game text strings** (for example clothing,
vehicle-part or menu item names) as data, to label things in the
companion. Where the game can supply the text at runtime, GTC reads
it from your own copy instead of storing it. Any table of such
strings in the repository names its source.

The HUD will refuse to light up on any other GTA IV game build.

The experimental GTA V plugin (`asi-v/`) likewise ships no Rockstar
binaries or media. It is story-mode only: in GTA Online it publishes
nothing but an "online" flag and reads no game state.

## Rockstar's modding guidelines

As of 2026, Rockstar Games' public modding guidelines permit
single-player mods for GTA IV and the Episodes, and explicitly allow
disabling anti-cheat protection for Story Mode.

GTC is designed to live safely inside those guidelines:

* **Single-player only.** Do not use this with GTA Online. The ASI
  resolves addresses pattern-based and does not touch any multiplayer
  code path, but running it alongside Online would still violate the
  guidelines.
* **Writes only when you ask.** The plugins read game state through
  published script natives and only change something when you press a
  button in the companion: radio retune, weapon switch, cheats, LS
  Customs, Ammu-Nation, Wardrobe. Nothing changes gameplay on its own.
* **No Rockstar asset redistribution.** The project exists as source
  code plus small data tables of in-game names. No binaries, media or
  scripts from the game are included; any binary artefact built from
  GTC is yours, and sharing it with the game bundled would violate
  Rockstar's rights.

## Third-party attribution

Pattern signatures, native-dispatcher strategy, and the per-frame hook
site for GTA IV Complete Edition 1.2.0.59 are derived from or
inspired by:

* **[GTAIV.EFLC.FusionFix](https://github.com/ThirteenAG/GTAIV.EFLC.FusionFix)**
  by ThirteenAG and contributors — GPL-3.0.
* **[Zolika1351/iv-sdk](https://github.com/Zolika1351/iv-sdk)** —
  historical native-hash tables and struct references for GTA IV
  1.0.7.0 / 1.0.8.0.
* **[Ultimate ASI Loader](https://github.com/ThirteenAG/Ultimate-ASI-Loader)**
  by ThirteenAG — the `dinput8.dll` proxy that loads
  `ThorBridge.asi` inside the Wine prefix. Not bundled; users
  download it directly from the upstream release.

For the GTA V plugin (`asi-v/`):

* **[Script Hook V](https://www.dev-c.com/gtav/scripthookv)** by
  Alexander Blade — closed-source freeware that the plugin calls at
  run time to invoke script natives. It is **not bundled, not
  redistributed and not linked at build time**; users download it
  from its author. `asi-v/` contains no code from Script Hook V or
  its SDK — it looks up the DLL's exported functions by name when it
  loads (see `asi-v/src/shv.cpp`).
* **[alloc8or/gta5-nativedb-data](https://github.com/alloc8or/gta5-nativedb-data)**
  — community reference used to check native names, identifiers and
  parameter lists.
* `asi-v/` contains no code derived from FusionFix or iv-sdk; the
  GTA IV attributions above apply to `asi/` only.

Wardrobe (clothing-slot) item names, if any ship with GTC, are
labelled in `app/src/main/assets/wardrobe/` and sourced from:

* _(placeholder — to be filled in when the Wardrobe names table lands;
  until then, GTC's Wardrobe shows generic "style N · colour M"
  labels and no third-party data is bundled.)_

The directly-ported helpers from the GPL-3.0 projects above are why
this project is also GPL-3.0 — the combined work must be. See
[`asi/NOTICE`](asi/NOTICE) for file-level attribution.

## No warranty

GPL-3.0 provides this software AS-IS with no warranty. The ASI
installs a per-frame hook into GTA IV's address space; while it has
been designed to fail safe (unrecognised exe → no hooks, no reads),
any such hook carries a non-zero risk of destabilising the game. Use
at your own risk; back up save files before first use.

## Trademarks

**Grand Theft Auto**, **GTA IV**, and all related marks are
trademarks of Take-Two Interactive Software, Inc. and Rockstar Games.
**AYN Thor** is a trademark of AYN Technologies Co., Ltd. This
project is not endorsed by, affiliated with, or sponsored by any of
them.
