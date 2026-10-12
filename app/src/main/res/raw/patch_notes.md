## v0.3.2

**Hotfix: stick lights restore the ambient colour**

When the wanted level dropped, GTC was leaving the thumbsticks dark. The direct LED driver now snapshots the current SN3112 state on the first beat of each chase and replays it on clear — so the sticks return to whatever you had before the sirens started, not to black.

If the sysfs reads come back empty (older firmware, SELinux denial), GTC falls back to the pre-hotfix behaviour and clears to black.

## v0.3.1

**Wanted lights**

The Thor's thumbsticks now flash red and blue while you have a wanted level. Tempo scales with stars — 600 ms per swap at 1★, dropping to 400 ms at full stars. Toggle from the bulb icon at the top right of the HUD.

**In-app updates**

GTC now checks for new GitHub releases in the background. When one is out, an arrow chip appears in the status bar — tap to download and install from inside the app. No more sideloading by hand.

**Translations**

HUD text is now localised in French, Spanish, German, Italian, Russian and Japanese — the languages Rockstar ships with GTA IV. Switch under Settings → Apps → GTC → App language on Android 13+.

**Credits**

The direct LED path is adapted from Pollux-MoonBench's Bifrost (archived). GPL-3.0, attributed in `app/NOTICE`.
