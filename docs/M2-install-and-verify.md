# M2 — Game-state reads: install and verify

Goal: prove the ASI can detect the game version, read live player state (health, max health, max armor), and log it from a background worker thread.

Install the same way as M1 (`docs/M1-install-and-verify.md`). The only change is a rebuilt `ThorBridge.asi`.

## Expected log output

```
[HH:MM:SS.mmm] === ThorBridge M2 loaded ===
[HH:MM:SS.mmm] [init] game version: classic 1.0.8.0
[HH:MM:SS.mmm] [init] exe fingerprint: crc32=0x???????? size=??????? label=unknown
[HH:MM:SS.mmm] [probe] worker thread started
[HH:MM:SS.mmm] [probe] no player yet (title/loading)
[HH:MM:SS.mmm] [probe] no player yet (title/loading)
...  (while on main menu / loading)
[HH:MM:SS.mmm] [probe] health=200.0 max_health=200 max_armor=100
[HH:MM:SS.mmm] [probe] health=200.0 max_health=200 max_armor=100
```

Record the `crc32=` and `size=` from your run — that fingerprint pins the Anker Games build in M8.

## Interpretations

| game version line           | meaning                                                                                     |
|-----------------------------|---------------------------------------------------------------------------------------------|
| `classic 1.0.7.0`           | Downgraded install. `probes::sample()` will work with the current offset table.             |
| `classic 1.0.8.0`           | Classic modding version. `probes::sample()` will work.                                      |
| `unknown`                   | Complete Edition 1.2.x, or a repack that stripped/rewrote the version resource. `probes::sample()` currently short-circuits — M8 adds CE/repack offsets. |

## Troubleshooting

- **No `health=` lines ever appear, only `no player yet`**: version detected as `unknown`, so `probes::sample()` is short-circuiting. Note the crc32/size for M8.
- **`health=` lines appear but values are garbage** (e.g., huge or NaN floats): wrong offsets for your build — i.e., the version resource claimed 1.0.8.0 but the game is actually CE. M8 fingerprint + table update will fix.
- **Crash on game launch**: likely wrong `GetPlayerInfo` function RVA for your build. Note the crc32 and remove the ASI until M8.

## Done when

The log shows a `game version:` line that matches your known install, and at least one `[probe] health=…` line with sensible values (health 100–200, max_health 100 or 200, max_armor 100 while on foot in-game).

Next milestone: **M3** — spin up a TCP listener on `127.0.0.1:47801`, serve the sampled state as newline-delimited JSON.
