# On-device validation (SUPERSEDED)

The original end-to-end validation guide assumed Game Native, Termux-based
testing of the TCP bridge, main-menu-only validation (which has no player
and only proves the DLL loaded), and `Presentation`-first bottom-screen
rendering. All of those are wrong — see `docs/STATE.md` for the audit.

Current validation path lives in `~/.claude/plans/purring-kindling-rainbow.md`,
one gate per phase.
