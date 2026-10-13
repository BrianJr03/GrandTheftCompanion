## v0.7.0

**NOTE:** both plugins changed in this release — download **ThorBridge.asi** (GTA IV) and **ThorBridgeV.asi** (GTA V) from the release assets and drop them next to `GTAIV.exe` / `GTA5.exe`. The APK update alone won't pick them up.

**Blips panel — route from the bottom screen**

Every icon on the game's map, nearest first, grouped by name, with a one-tap **route** button per row. Opens the game's own GPS to that spot without pausing or dragging a cursor around. Works on GTA IV and GTA V.

Tap the pin icon in the status bar to open it. On GTA IV, dpad-down on the controller also toggles it (reads the game's own pad state, so it works even while you're playing). Multiple instances of the same icon collapse into one row with a count badge; expand to pick which one. Tapping an already-routed row clears the route.

**Car chip + auto-swipe to the map**

A car icon slides in next to the pin whenever you're in a vehicle. Tap it to jump straight to the minimap. Or flip on **Auto-swipe to map while driving** in the new settings sheet and it does it for you.

**Settings sheet**

Gear icon in the top-right of the paused / setup / character-switch screens. Expandable sections (Minimap first) with three toggles:
- Auto-swipe to the map tab when you get in a vehicle.
- Always return to the HUD on exit (overrides the default smart-restore).
- Start the mirror automatically when the connection goes live.

**GTA IV fixes**

Two bugs the first on-device run surfaced: the blip walk was reading only two of the game's internal icons (now reads them all), and every blip's distance was reading the same number because the coord native wanted a different argument shape than documented. Both fixed.

**Translations**

Full fr/es/de/it/ru/ja for everything new.
