#pragma once

// Minimal RGBA8 → PNG encoder. The DEFLATE stream inside IDAT consists of
// stored-only (uncompressed) blocks, so the output is a strictly valid PNG
// (readable by every PNG decoder) but larger on-disk than a Zopfli'd one.
// We ship pixel data over a 127.0.0.1 socket on the device, so the few
// extra megabytes do not matter; this keeps the encoder clean-room, under
// 300 lines, and dependency-free.
//
// Returns the encoded byte buffer by appending to `out` and returning its
// final size. The caller owns the storage.

#include <cstddef>
#include <cstdint>
#include <vector>

namespace thorbridge::png {

// Encodes an RGBA8 image. `width` and `height` must be positive and the
// `rgba` buffer must hold width*height*4 bytes in row-major, top-left-
// origin layout (R, G, B, A per pixel). `out` is appended to, not cleared.
// Returns false if any dimension is non-positive.
bool encode_rgba(const uint8_t* rgba, int width, int height,
                 std::vector<uint8_t>& out);

}  // namespace thorbridge::png
