# M3 — TCP bridge: install and verify

Goal: confirm the ASI runs a TCP server on `127.0.0.1:47801` that pushes newline-delimited JSON player-state frames at 20 Hz, **and that a separate Android process can connect to it across the Game Native sandbox boundary.** This is the single most load-bearing assumption of the whole project — if localhost TCP doesn't cross from Wine into Android, we need to pivot to shared files (slower + storage-permission ugliness).

Install the same way as M1/M2 — replace `ThorBridge.asi` in GTA IV's install dir, launch the game.

## Expected log output

```
[HH:MM:SS.mmm] === ThorBridge M3 loaded ===
[HH:MM:SS.mmm] [init] game version: classic 1.0.8.0
[HH:MM:SS.mmm] [init] exe fingerprint: crc32=0x???????? size=??????? label=unknown
[HH:MM:SS.mmm] [bridge] listening on 127.0.0.1:47801
[HH:MM:SS.mmm] [heartbeat] no player (title/loading)
...
[HH:MM:SS.mmm] [bridge] client connected
[HH:MM:SS.mmm] [bridge] client disconnected
[HH:MM:SS.mmm] [heartbeat] health=200.0 max_health=200 max_armor=100
```

If `[bridge] bind on port 47801 failed: 10048` (WSAEADDRINUSE) ever appears, something else on the Thor is bound to 47801. The `SO_REUSEADDR` set on the listener handles the "my last game crashed, port still in TIME_WAIT" case automatically; a hard conflict means another app on the Thor claimed the port.

## Verify from the Thor itself (easiest)

1. Install **Termux** on the Thor from F-Droid (Play Store Termux is deprecated).
2. In Termux: `pkg install netcat-openbsd`
3. Launch GTA IV via Game Native. Wait for `[bridge] listening` to appear in `ThorBridge.log`.
4. In Termux: `nc 127.0.0.1 47801`

Expected: a stream of JSON lines at 20 Hz. Pre-game:
```
{"t":12345,"valid":false}
{"t":12395,"valid":false}
```
In-game:
```
{"t":45678,"valid":true,"health":200.0,"max_health":200,"max_armor":0}
{"t":45728,"valid":true,"health":200.0,"max_health":200,"max_armor":0}
```

**If this works, M3 is done.** If `nc` says "Connection refused," the server didn't come up — check `ThorBridge.log` for a `[bridge]` error line. If `nc` hangs with no connection error AND no data, the port is open but the per-client thread isn't sending — unexpected, needs investigation.

## Verify from a laptop (easier dev loop)

If you don't want to install Termux, you can temporarily bind the listener to all interfaces so a laptop on the same wifi can connect:

```
cd asi
rm -rf build
./build.sh -DTHORBRIDGE_LAN_DEBUG=1    # see note below
```

Note: `build.sh` doesn't currently pass CMake defines through. Either edit `CMakeLists.txt` to add `add_compile_definitions(THORBRIDGE_LAN_DEBUG=1)` for the LAN run, or export it via the build.sh invocation of cmake. From the laptop:

```
nc <thor-ip> 47801
```

**Revert to loopback-only before shipping.** No reason to expose game-state on the LAN in production.

## What this proves

If `nc 127.0.0.1 47801` from Termux on the Thor sees the JSON stream, we've confirmed:
1. ASI startup works end-to-end (M1 ✓ for real)
2. Game-state reads work (M2 ✓ for real)
3. **Localhost TCP crosses the Game Native sandbox** — this is the big one
4. The 20 Hz push rate is sustainable without starving the game

M4 onward builds the Android companion that consumes this stream.

## Done when

`nc` on the Thor sees a steady 20 Hz JSON stream, and `health=` values track your in-game state (take damage → number drops; buy a vest → `max_armor` moves).

Next milestone: **M4** — scaffold the Jetpack Compose companion with a foreground service that consumes the stream.
