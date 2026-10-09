#pragma once

// Append-to-file logger used by all ASI translation units. Writes to
// `ThorBridge.log` next to GTAIV.exe (the game's CWD on launch). Each line
// is prefixed with wall-clock HH:MM:SS.mmm so interleaved thread output is
// still interpretable.

void thor_log(const char* fmt, ...);
