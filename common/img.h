#pragma once

// Minimal reader for the IMG archive formats used by the GTA series.
// This pass covers the plain "VER2" variant (San Andreas, GTA IV Complete
// Edition prediction per Brian's note). The on-disk layout for VER2 is:
//
//   bytes 0..3   : magic 'V' 'E' 'R' '2'
//   bytes 4..7   : u32 LE, number of entries
//   bytes 8..   : repeating 32-byte entries:
//                    offset_sectors : u32 LE   (sector = 2048 bytes)
//                    streaming_size : u16 LE   (in sectors)
//                    size_sectors   : u16 LE   (usually 0; use streaming)
//                    name           : 24 bytes, NUL-terminated
//
// The actual entry bodies live at offset_sectors * 2048 and span
// (streaming_size * 2048) bytes.
//
// Any other magic (encrypted VER3, EFLC variants, etc.) is logged with
// its first 32 bytes and parsing is refused so the caller can decide
// what to do.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace thorbridge::img {

struct Entry {
    std::string name;
    std::uint64_t offset;   // absolute byte offset into the archive
    std::uint32_t size;     // size in bytes (sector-aligned at least)
};

struct ParseResult {
    bool                 ok = false;
    std::string          error;         // human-readable, safe to log
    std::vector<Entry>   entries;
    // When the magic was not VER2: a hex dump of the first 32 bytes so
    // the atlas worker can log it for Gate IV-1 to interpret.
    std::string          head_hex;
};

// Reads the TOC of a buffer that is already in memory. Does not touch
// the entry bodies.
ParseResult parse_toc(const std::uint8_t* bytes, std::size_t n);

}  // namespace thorbridge::img
