# M3 — TCP bridge: install and verify (SUPERSEDED)

M3's validation steps required Termux on the Thor or a LAN debug build.
Neither is needed — `adb forward tcp:47801 tcp:47801` from the Mac, then
`nc 127.0.0.1 47801` on the Mac, is sufficient.

M3 also assumed the launcher was Game Native and that the ASI loader
auto-override worked without `WINEDLLOVERRIDES`. See `docs/STATE.md` for
the audit and the Phase 1 section of
`~/.claude/plans/purring-kindling-rainbow.md` for the current path.
