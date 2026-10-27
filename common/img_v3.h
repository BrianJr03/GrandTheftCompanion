#pragma once

// GTA IV IMG v3 archive reader. Layout (validated against the user's
// own radar.img on 2026-10-27):
//
//   bytes  0..15   AES-256-ECB x16 encrypted — magic 0xA94E2A52 (LE),
//                  version=3, item_count, table_size
//   bytes 16..17   plain — item_size = 0x0010 (16)
//   bytes 18..19   plain — unknown    = 0x00E9
//   bytes 20..    encrypted — item_count * 16-byte TOC entries
//                  (size_or_rsc_flags, resource_type, offset_block,
//                   used_blocks, flags)
//   then          encrypted — filename table, NUL-terminated strings
//                  packed; the trailing partial 16-byte block of this
//                  region is left plaintext per X-Seti's observation
//                  (the "last name is garbage" bug its tool fixed).
//
// File bodies at offset_block * 2048 are plaintext (no decryption).
//
// This file ships NO key bytes. The caller supplies a 32-byte AES-256
// key recovered from the user's own GTAIV.exe via keys_iv::locate.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "aes.h"

namespace thorbridge::img_v3 {

constexpr std::uint32_t kMagic = 0xA94E2A52;
constexpr std::uint32_t kVersion = 3;
constexpr std::size_t   kSector = 2048;
constexpr std::size_t   kEntryBytes = 16;
constexpr std::size_t   kAesRounds = 16;

struct Entry {
    std::string   name;
    std::uint64_t offset;     // absolute byte offset into the archive
    std::uint32_t size;       // for plain entries; informational for RSC
    bool          is_rsc;
    std::uint32_t rsc_flags;  // only meaningful when is_rsc
    std::uint32_t resource_type;
};

struct ParseResult {
    bool                 ok = false;
    std::string          error;
    std::vector<Entry>   entries;
    std::uint32_t        item_count = 0;
    std::uint32_t        table_size = 0;
    // Hex of the first 32 bytes of the decrypted header + entry 0, for
    // on-device diagnostics.
    std::string          head_hex_plain;
};

// `bytes` is the full archive buffer; `key` is the 32-byte AES-256 key
// recovered from GTAIV.exe. Returns ok=false with an informative error
// string on any inconsistency (bad magic post-decrypt, truncated TOC,
// entry extending past the archive).
ParseResult parse_toc(const std::uint8_t* bytes, std::size_t bytes_size,
                      const std::uint8_t key[aes::kKeyBytes]);

}  // namespace thorbridge::img_v3
