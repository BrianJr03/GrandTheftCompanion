#pragma once

// GTA V RSC7 resource header + body decompression. Resources inside
// RPF7 archives (ytd, ydr, ydd, ymap, etc.) are wrapped in a 16-byte
// RSC7 header followed by a raw-deflate body. The header records a
// `version` plus two "memory section" flag words (system + graphics)
// whose packed power-of-two decomposition tells the game how much
// system RAM and GPU RAM the loaded resource will occupy.
//
// Layout, per Neodymium's RpfFile.cs in CodeWalker (MIT):
//
//   +00 magic           uint32  == 0x37435352 ("RSC7" LE)
//   +04 version         uint32
//   +08 system_flags    uint32
//   +0C graphics_flags  uint32
//   +10 body            raw-deflate stream (RFC 1951, no zlib wrap).
//                       CodeWalker falls back to the body verbatim if
//                       deflate fails.
//
// The flags word decomposition (same shape for system_flags and
// graphics_flags) is dexyfex's cleaned-up variant of the original
// Neodymium formula at RpfFile.cs line 2318:
//
//   ss = flags & 0xF                            (size-shift exponent)
//   base_size = 0x200 << ss                     (512, 1024, ..., 16 MB)
//   s0..s8 are counts at bit positions {27, 26, 25, 24, 17..23,
//                                        11..16, 7..10, 5..6, 4}
//   multipliers 1, 2, 4, 8, 16, 32, 64, 128, 256
//   size = base_size * sum(s_i * multiplier_i)
//
// Only decompression is implemented here; writing the wrapper is a
// separate concern we don't ship.

#include <cstddef>
#include <cstdint>
#include <vector>

namespace thorbridge::rsc7 {

constexpr std::uint32_t kMagic       = 0x37435352u;  // 'RSC7' LE
constexpr std::size_t   kHeaderBytes = 16;

struct Header {
    std::uint32_t version         = 0;
    std::uint32_t system_flags    = 0;
    std::uint32_t graphics_flags  = 0;
    // Derived values (filled by parse_header).
    std::uint32_t system_size     = 0;
    std::uint32_t graphics_size   = 0;
    std::uint32_t system_type     = 0;  // top 4 bits of system_flags
    std::uint32_t graphics_type   = 0;  // top 4 bits of graphics_flags
};

// Decompose a 32-bit flags field into its memory-section byte size.
// Used by parse_header for the system and graphics halves and
// available standalone for callers that want to size specific blobs
// without parsing a header.
std::uint32_t size_from_flags(std::uint32_t flags);

// Parse a 16-byte RSC7 header starting at `data[0..15]`. Fills
// `out.version`, both raw flag words, and both derived sizes. Returns
// false if `data_bytes < 16` or the magic does not match. The caller
// still owns `data`; we only read.
bool parse_header(const std::uint8_t* data, std::size_t data_bytes,
                  Header& out);

// Decompress the body region of a resource. `body` is the bytes AFTER
// the 16-byte RSC7 header. If the deflate stream decodes cleanly,
// `out` holds the decompressed payload. If decompression fails
// (CodeWalker's typical case for already-raw payloads), `out` is
// populated with the body verbatim and the function still returns
// true. Returns false only if `body` is null or `body_bytes` is zero.
bool decompress_body(const std::uint8_t* body, std::size_t body_bytes,
                     std::vector<std::uint8_t>& out);

// Convenience: parse header + decompress body in one call.
// `data` points at the start of the resource (the RSC7 magic);
// `data_bytes` is the full resource size (header + body).
bool open(const std::uint8_t* data, std::size_t data_bytes,
          Header& header_out, std::vector<std::uint8_t>& payload_out);

}  // namespace thorbridge::rsc7
