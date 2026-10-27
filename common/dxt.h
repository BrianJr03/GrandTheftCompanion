#pragma once

// Clean-room BC1 (DXT1) / BC3 (DXT5) decoders. Written from the published
// S3TC / BPTC descriptions in the DX11 reference and Microsoft's
// "block-compression formats" article; no third-party code was consulted.
//
// Each block is 4x4 pixels. The caller provides the compressed buffer, the
// image dimensions (which must be multiples of 4), and an RGBA8 output
// buffer sized exactly width*height*4. Decoding is bottom-up as DX stores
// them: no flip is applied here — PNG writers flip when they emit scanlines.

#include <cstddef>
#include <cstdint>

namespace thorbridge::dxt {

enum class Format {
    BC1,   // DXT1 — RGB or RGB + 1-bit alpha, 8 bytes per 4x4 block
    BC3,   // DXT5 — RGBA with an 8-bit alpha channel, 16 bytes per block
};

// Decodes `src` into `rgba` (width*height*4 bytes). Returns false if the
// dimensions are not multiples of 4 or `src_bytes` is too short for the
// implied block count. `rgba` layout is row-major, top-left origin, with
// each pixel stored as R, G, B, A.
bool decode(Format fmt,
            const uint8_t* src, std::size_t src_bytes,
            int width, int height,
            uint8_t* rgba);

// Convenience: expected compressed size for `width`×`height` in `fmt`.
// Returns 0 if dimensions are not multiples of 4.
std::size_t compressed_size(Format fmt, int width, int height);

}  // namespace thorbridge::dxt
