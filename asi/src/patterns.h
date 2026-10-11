#pragma once

#include <cstddef>
#include <cstdint>

// patterns:: — generic IDA-style byte-pattern scanner over the running
// GTAIV.exe. Each pattern is a space-separated string of hex bytes, where
// `??` is a wildcard that matches any byte. Example:
//
//     patterns::find("8B 44 24 ?? 50 E8 ?? ?? ?? ??");
//
// Scans are O(n*m) over the .text section but n ~= 10 MB and m ~= 20, so
// per-startup latency is a few milliseconds total. We fail hard if a
// pattern matches zero times OR more than once — ambiguity means we have
// the wrong pattern.

namespace patterns {

// Address of the first match, or 0 if `pat` matches zero or multiple times
// in the running exe's .text section. Logs failures via thor_log.
uintptr_t find(const char* name, const char* pat);

}  // namespace patterns
