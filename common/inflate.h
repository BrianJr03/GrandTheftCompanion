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
// is true the function first consumes a 2-byte zlib header. If
// `check_adler` is additionally true (default), it verifies the trailing
// 4-byte Adler-32 (big-endian) at `src[src_bytes - 4]` against the
// output. Set `check_adler=false` for streams that sit inside a larger
// container with trailing padding past the real Adler-32 (e.g. GTA IV
// RSC bodies, which are 2 KB sector-rounded inside IMG v3).
//
// Returns false if the stream is malformed, truncated, uses features
// outside the subset supported here, or (when the Adler check is on)
// the trailer mismatches the recovered bytes.
bool decompress(const uint8_t* src, std::size_t src_bytes,
                std::vector<uint8_t>& out,
                bool expect_zlib,
                bool check_adler = true);

}  // namespace thorbridge::inflate
