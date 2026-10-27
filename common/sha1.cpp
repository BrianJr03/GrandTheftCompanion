#include "sha1.h"

#include <cstring>

namespace thorbridge::sha1 {

namespace {

inline std::uint32_t rol(std::uint32_t v, int n) {
    return (v << n) | (v >> (32 - n));
}

void process_block(std::uint32_t h[5], const std::uint8_t block[64]) {
    std::uint32_t w[80];
    for (int i = 0; i < 16; ++i) {
        w[i] = (static_cast<std::uint32_t>(block[i * 4 + 0]) << 24) |
               (static_cast<std::uint32_t>(block[i * 4 + 1]) << 16) |
               (static_cast<std::uint32_t>(block[i * 4 + 2]) <<  8) |
                static_cast<std::uint32_t>(block[i * 4 + 3]);
    }
    for (int i = 16; i < 80; ++i) {
        w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    }
    std::uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
    for (int i = 0; i < 80; ++i) {
        std::uint32_t f, k;
        if (i < 20)      { f = (b & c) | ((~b) & d); k = 0x5a827999u; }
        else if (i < 40) { f = b ^ c ^ d;             k = 0x6ed9eba1u; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdcu; }
        else             { f = b ^ c ^ d;             k = 0xca62c1d6u; }
        std::uint32_t t = rol(a, 5) + f + e + k + w[i];
        e = d; d = c; c = rol(b, 30); b = a; a = t;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
}

}  // namespace

void hash(const std::uint8_t* data, std::size_t bytes,
          std::uint8_t digest[kDigestBytes]) {
    std::uint32_t h[5] = {
        0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u, 0xc3d2e1f0u,
    };
    std::uint8_t block[64];
    std::size_t full = bytes & ~std::size_t{63};
    for (std::size_t off = 0; off < full; off += 64) {
        process_block(h, data + off);
    }
    // Pad the final partial block: 0x80, zeros, 8-byte big-endian bit
    // length. Spans one or two 64-byte blocks depending on tail size.
    std::size_t tail = bytes - full;
    std::memcpy(block, data + full, tail);
    block[tail] = 0x80;
    if (tail + 1 > 56) {
        std::memset(block + tail + 1, 0, 64 - tail - 1);
        process_block(h, block);
        std::memset(block, 0, 56);
    } else {
        std::memset(block + tail + 1, 0, 56 - tail - 1);
    }
    std::uint64_t bitlen = static_cast<std::uint64_t>(bytes) * 8ull;
    for (int i = 0; i < 8; ++i) {
        block[63 - i] = static_cast<std::uint8_t>((bitlen >> (i * 8)) & 0xff);
    }
    process_block(h, block);
    for (int i = 0; i < 5; ++i) {
        digest[i * 4 + 0] = static_cast<std::uint8_t>((h[i] >> 24) & 0xff);
        digest[i * 4 + 1] = static_cast<std::uint8_t>((h[i] >> 16) & 0xff);
        digest[i * 4 + 2] = static_cast<std::uint8_t>((h[i] >>  8) & 0xff);
        digest[i * 4 + 3] = static_cast<std::uint8_t>( h[i]        & 0xff);
    }
}

bool equals(const std::uint8_t a[kDigestBytes], const std::uint8_t b[kDigestBytes]) {
    return std::memcmp(a, b, kDigestBytes) == 0;
}

}  // namespace thorbridge::sha1
