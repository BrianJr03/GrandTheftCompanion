# M2 — Game-state reads: install and verify (SUPERSEDED)

M2's probe code reads raw offsets from `Zolika1351/iv-sdk`, which only
targets GTA IV 1.0.7.0 and 1.0.8.0. The device actually runs **Complete
Edition 1.2.0.59**, where those offsets are wrong. The current code
short-circuits on `GameVersion::Unknown`, so M2 would just log
`no player (title/loading)` forever on CE — not an actual read.

Phase 2 replaces this with byte-pattern scans and script natives, called
from the game's own thread. See `docs/STATE.md` and the Phase 2 section of
`~/.claude/plans/purring-kindling-rainbow.md`.
