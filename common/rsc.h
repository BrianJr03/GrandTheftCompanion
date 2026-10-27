#pragma once

// Rockstar Advanced Game Engine resource container ("RSC") reader.
//
// GTA IV uses version 5 of the RSC header for most resources, with a
// version-nested field word identifying the resource type. The on-
// disk layout of a GTA IV RSC entry inside an IMG v3 archive is:
//
//   bytes  0..3   magic: 'R' 'S' 'C' 0x05
//   bytes  4..7   resource version word (little-endian)
//                   WTD uses 8
//                   (other types: WFT=9, WDR=6, WBD=6, ...)
//   bytes  8..11  resource flags (unused by this reader; the body
//                 after the header is a self-describing zlib stream)
//   bytes 12..    zlib-compressed body (system memory + graphics
//                 memory sections concatenated after inflation)
//
// This module only strips the 12-byte header and inflates the body.
// The resource-type-specific layout inside the inflated buffer is
// the caller's concern.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace thorbridge::rsc {

constexpr std::uint32_t kMagic = 0x05435352;   // 'RSC' + 0x05, LE word

struct Inflated {
    bool                       ok = false;
    std::string                error;
    std::uint32_t              resource_version = 0;
    std::uint32_t              flags = 0;
    std::vector<std::uint8_t>  body;   // inflated contents
};

// Inflate a GTA IV RSC entry. `bytes` points at the first byte of the
// RSC header (not into the IMG archive).
Inflated inflate(const std::uint8_t* bytes, std::size_t n);

}  // namespace thorbridge::rsc
