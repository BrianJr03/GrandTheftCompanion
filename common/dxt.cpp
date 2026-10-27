#include "dxt.h"

#include <cstring>

namespace thorbridge::dxt {

namespace {

struct Rgb { uint8_t r, g, b; };

// RGB565 → RGB888 with the standard low-bit replication (so 0b11111 maps
// to 0xFF, not 0xF8). The replicated bits are the top bits of the source
// shifted into the low bits of the destination.
Rgb unpack565(uint16_t v) {
    uint8_t r = static_cast<uint8_t>((v >> 11) & 0x1F);
    uint8_t g = static_cast<uint8_t>((v >> 5)  & 0x3F);
    uint8_t b = static_cast<uint8_t>( v        & 0x1F);
    return {
        static_cast<uint8_t>((r << 3) | (r >> 2)),
        static_cast<uint8_t>((g << 2) | (g >> 4)),
        static_cast<uint8_t>((b << 3) | (b >> 2)),
    };
}

Rgb lerp(const Rgb& a, const Rgb& b, int wa, int wb, int wsum) {
    return {
        static_cast<uint8_t>((a.r * wa + b.r * wb) / wsum),
        static_cast<uint8_t>((a.g * wa + b.g * wb) / wsum),
        static_cast<uint8_t>((a.b * wa + b.b * wb) / wsum),
    };
}

// Decode one 8-byte DXT1 colour block. If `allow_1bit_alpha` is true (the
// BC1-standalone case), the 3-colour mode treats index 3 as transparent
// black. BC3's embedded colour block has alpha driven elsewhere, so the
// third mode is not reachable there (c0 > c1 always for the colour part);
// we still implement the full table so a buggy encoder won't produce
// uninitialised reads.
void decode_colour_block(const uint8_t* b, bool allow_1bit_alpha,
                         Rgb out_rgb[16], uint8_t out_a[16]) {
    uint16_t c0 = static_cast<uint16_t>(b[0] | (b[1] << 8));
    uint16_t c1 = static_cast<uint16_t>(b[2] | (b[3] << 8));
    Rgb p[4];
    p[0] = unpack565(c0);
    p[1] = unpack565(c1);
    if (c0 > c1) {
        p[2] = lerp(p[0], p[1], 2, 1, 3);
        p[3] = lerp(p[0], p[1], 1, 2, 3);
        for (int i = 0; i < 16; ++i) out_a[i] = 0xFF;
    } else {
        p[2] = lerp(p[0], p[1], 1, 1, 2);
        p[3] = {0, 0, 0};
        for (int i = 0; i < 16; ++i) out_a[i] = 0xFF;
    }
    uint32_t bits = static_cast<uint32_t>(
        b[4] | (b[5] << 8) | (b[6] << 16) | (b[7] << 24));
    for (int i = 0; i < 16; ++i) {
        unsigned idx = (bits >> (i * 2)) & 0x3;
        out_rgb[i] = p[idx];
        if (allow_1bit_alpha && c0 <= c1 && idx == 3) out_a[i] = 0x00;
    }
}

// Decode one 8-byte BC3 alpha block into 16 alpha samples. The two alpha
// endpoints are 8-bit; the 16 three-bit selectors occupy the next 48
// bits, little-endian, lsb-first within the 48-bit stream.
void decode_alpha_block(const uint8_t* b, uint8_t out_a[16]) {
    uint8_t a0 = b[0], a1 = b[1];
    uint8_t a[8];
    a[0] = a0;
    a[1] = a1;
    if (a0 > a1) {
        for (int i = 1; i <= 6; ++i) {
            a[i + 1] = static_cast<uint8_t>(((7 - i) * a0 + i * a1) / 7);
        }
    } else {
        for (int i = 1; i <= 4; ++i) {
            a[i + 1] = static_cast<uint8_t>(((5 - i) * a0 + i * a1) / 5);
        }
        a[6] = 0x00;
        a[7] = 0xFF;
    }
    uint64_t sel = 0;
    for (int i = 0; i < 6; ++i) {
        sel |= static_cast<uint64_t>(b[2 + i]) << (i * 8);
    }
    for (int i = 0; i < 16; ++i) {
        unsigned idx = static_cast<unsigned>((sel >> (i * 3)) & 0x7);
        out_a[i] = a[idx];
    }
}

void write_block_to_image(const Rgb rgb[16], const uint8_t a[16],
                          int bx, int by, int width, int height,
                          uint8_t* rgba) {
    for (int py = 0; py < 4; ++py) {
        int y = by + py;
        if (y >= height) continue;
        for (int px = 0; px < 4; ++px) {
            int x = bx + px;
            if (x >= width) continue;
            int i = py * 4 + px;
            uint8_t* dst = rgba + (y * width + x) * 4;
            dst[0] = rgb[i].r;
            dst[1] = rgb[i].g;
            dst[2] = rgb[i].b;
            dst[3] = a[i];
        }
    }
}

}  // namespace

std::size_t compressed_size(Format fmt, int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    if ((width % 4) != 0 || (height % 4) != 0) return 0;
    std::size_t blocks = static_cast<std::size_t>(width / 4) *
                         static_cast<std::size_t>(height / 4);
    return blocks * (fmt == Format::BC1 ? 8u : 16u);
}

bool decode(Format fmt,
            const uint8_t* src, std::size_t src_bytes,
            int width, int height,
            uint8_t* rgba) {
    std::size_t need = compressed_size(fmt, width, height);
    if (need == 0 || src_bytes < need || !src || !rgba) return false;
    int block_size = (fmt == Format::BC1 ? 8 : 16);
    int w_blocks = width / 4;
    int h_blocks = height / 4;
    for (int by = 0; by < h_blocks; ++by) {
        for (int bx = 0; bx < w_blocks; ++bx) {
            const uint8_t* blk = src + (by * w_blocks + bx) * block_size;
            Rgb rgb[16];
            uint8_t a[16];
            if (fmt == Format::BC1) {
                decode_colour_block(blk, /*allow_1bit_alpha=*/true, rgb, a);
            } else {
                decode_alpha_block(blk, a);
                uint8_t ignored[16];
                decode_colour_block(blk + 8, /*allow_1bit_alpha=*/false,
                                    rgb, ignored);
            }
            write_block_to_image(rgb, a, bx * 4, by * 4, width, height, rgba);
        }
    }
    return true;
}

}  // namespace thorbridge::dxt
