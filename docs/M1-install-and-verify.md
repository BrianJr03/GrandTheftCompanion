# M1 — ASI hello world: install and verify

Goal: prove that `ThorBridge.asi` loads inside GTA IV when launched via Game Native, by confirming a log line hits disk inside the Wine prefix.

## Prereqs (one-time)

On the macOS dev host:
```
brew install cmake mingw-w64
```

On the Thor:
- Game Native installed and able to launch GTA IV already
- Ultimate ASI Loader (`dinput8.dll`) in the GTA IV install dir *(if not, download v1.84+ from https://github.com/ThirteenAG/Ultimate-ASI-Loader/releases)*
- A file manager or ADB access to read files from Game Native's app-private storage (likely needs USB debugging on the Thor)

## Build on the Mac

```
cd ~/AndroidStudioProjects/ThorHUD-GTAIV/asi
./build.sh
# → build/ThorBridge.asi
```

`file build/ThorBridge.asi` should report `PE32 executable (DLL) (console) Intel 80386, for MS Windows`.

## Install on the Thor

The target directory is the GTA IV install folder inside Game Native's per-game Wine prefix. In Game Native, long-press GTA IV → "Open install folder" (or similar — exact wording varies by version). Copy:
- `ThorBridge.asi`
- `dinput8.dll` *(Ultimate ASI Loader, if not already present)*

...into that folder.

## Verify

1. Launch GTA IV via Game Native. Play through main-menu to confirm nothing crashed.
2. Quit GTA IV.
3. From that same install folder, look for `ThorBridge.log`. It should contain:
   ```
   [ThorBridge] loaded — M1 hello world
   [ThorBridge] unloaded
   ```

If no `ThorBridge.log` appears:
- Confirm Game Native did not strip the `.asi` extension on import.
- Confirm `dinput8.dll` is the real UAL and not something else (`strings dinput8.dll | grep -i ASI` should mention "ASI Loader").
- Check GTA IV didn't silently disable DLL overrides. In Game Native's per-game settings, look for "Wine DLL overrides" and ensure `dinput8=n,b` is present (native then builtin).

## Done when

The log line appears after a clean game launch + quit cycle. That validates:
- MinGW-w64 i686 cross-compile toolchain works
- Game Native loads our ASI via UAL
- We have a file-based signal from inside the game process we can iterate against

Next milestone: **M2** — pull live natives via IV-SDK and dump them to the same log.
