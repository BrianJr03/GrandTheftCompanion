#pragma once

// Clean-room RFC 1951 (DEFLATE) inflater, with an optional 2-byte zlib
// header + 4-byte Adler-32 trailer wrapper (RFC 1950). Written against
// the normative specs; no third-party source was consulted.
//
// Scope: enough to decompress the zlib-wrapped bodies of GTA IV RSC
// resources (WTD / WDR / WFT etc.) and the stored-DEFLATE PNGs we
// produce ourselves. Fixed and dynamic Huffman block types are
// supported; the stream must fit in memory (the inflater keeps a 32 KB
// sliding window plus the full output in a std::vector).

#include <cstddef>
#include <cstdint>
#include <vector>

namespace thorbridge::inflate {

// Decompress `src` into `out`, appending bytes to `out`. If `expect_zlib`
// is true the function first consumes a 2-byte zlib header, inflates the
// DEFLATE stream, then verifies a trailing 4-byte Adler-32 (big-endian)
// against the output. Returns true on success.
//
// Returns false if the stream is malformed, truncated, uses features
// outside the subset supported here, or (when `expect_zlib` is true) the
// Adler-32 check fails.
bool decompress(const uint8_t* src, std::size_t src_bytes,
                std::vector<uint8_t>& out,
                bool expect_zlib);

}  // namespace thorbridge::inflate
