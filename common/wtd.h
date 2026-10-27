#pragma once

// grcTextureDictionary ("WTD") reader for GTA IV radar tiles.
//
// A radar WTD is a resource whose system-memory section carries one
// grcTexturePC (80-byte struct, offsets per DK22Pac/plugin-sdk's
// grcTexture.h and FusionFix's comvars.ixx) describing a DXT1 or DXT5
// image, and whose graphics-memory section carries the raw pixel
// blocks. The two sections sit back-to-back inside the inflated RSC
// body: the system half holds the pgDictionary + grcTexturePC +
// texture name, and the graphics half is the pixel data.
//
// Finding the pixel data without parsing the RSC's packed section-
// sizes word: we scan the inflated body for the DXT1 / DXT5 fourcc
// (located at offset 0x20 of a grcTexturePC), read width + height
// from offsets 0x1C / 0x1E of the enclosing struct, and point at the
// LAST (width*height / 2 for DXT1, width*height for DXT5) bytes of
// the inflated buffer — the graphics-memory section is the tail.
//
// This heuristic works for the radar tiles because they are simple
// one-texture dictionaries with no mip chain worth decoding and no
// trailing padding past the pixel block.

#include <cstddef>
#include <cstdint>
#include <string>

#include "dxt.h"

namespace thorbridge::wtd {

constexpr std::uint32_t kD3dFmtDxt1 = 0x31545844;  // 'DXT1' LE
constexpr std::uint32_t kD3dFmtDxt3 = 0x33545844;  // 'DXT3' LE (BC2)
constexpr std::uint32_t kD3dFmtDxt5 = 0x35545844;  // 'DXT5' LE (BC3)

struct Texture {
    bool               ok = false;
    std::string        error;
    std::string        name;         // texture name, may be empty
    int                width = 0;
    int                height = 0;
    dxt::Format        format = dxt::Format::BC1;
    const std::uint8_t* pixels = nullptr;  // points into the body buffer
    std::size_t        pixel_bytes = 0;
};

// Locate the single radar texture inside an inflated RSC body. Returns
// a Texture whose `pixels` points into `inflated_body` — the body must
// outlive the Texture. Fails if no DXT1 / DXT5 fourcc is found or the
// width/height look invalid.
Texture find_tile(const std::uint8_t* inflated_body, std::size_t body_bytes);

}  // namespace thorbridge::wtd
