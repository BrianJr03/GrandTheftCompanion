#include "png.h"

#include <cstring>

namespace thorbridge::png {

namespace {

constexpr std::size_t kStoredBlockMax = 65535;

// --- CRC-32 (IEEE 802.3) ------------------------------------------------
// Table-less CRC-32: the polynomial is applied byte-wise, no third-party
// table copied. On the host (desktop test) and the Thor's game thread the
// cost is negligible for a 6-tile atlas.
uint32_t crc32_update(uint32_t crc, const uint8_t* data, std::size_t n) {
    crc = ~crc;
    for (std::size_t i = 0; i < n; ++i) {
        crc ^= data[i];
        for (int b = 0; b < 8; ++b) {
            uint32_t mask = static_cast<uint32_t>(-static_cast<int32_t>(crc & 1u));
            crc = (crc >> 1) ^ (0xEDB88320u & mask);
        }
    }
    return ~crc;
}

uint32_t crc32(const uint8_t* data, std::size_t n) {
    return crc32_update(0u, data, n);
}

// --- Adler-32 -----------------------------------------------------------
// Standard streaming Adler-32 keyed by prime 65521.
uint32_t adler32(const uint8_t* data, std::size_t n) {
    uint32_t a = 1, b = 0;
    for (std::size_t i = 0; i < n; ++i) {
        a = (a + data[i]) % 65521u;
        b = (b + a) % 65521u;
    }
    return (b << 16) | a;
}

// --- Byte emitters ------------------------------------------------------

void put_u32_be(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >>  8) & 0xFF));
    out.push_back(static_cast<uint8_t>( v        & 0xFF));
}

void put_u16_le(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

// Write one PNG chunk: 4-byte length, 4-byte type, data, 4-byte CRC.
void write_chunk(std::vector<uint8_t>& out,
                 const char type[4],
                 const uint8_t* data, std::size_t n) {
    put_u32_be(out, static_cast<uint32_t>(n));
    std::size_t crc_from = out.size();
    out.insert(out.end(), type, type + 4);
    if (n > 0) out.insert(out.end(), data, data + n);
    uint32_t crc = crc32(out.data() + crc_from, 4 + n);
    put_u32_be(out, crc);
}

// Build the raw IDAT payload: for each row, a filter byte (0 = None) then
// width*4 bytes of RGBA. The caller then wraps this in zlib + stored
// DEFLATE blocks.
std::vector<uint8_t> build_scanlines(const uint8_t* rgba,
                                     int width, int height) {
    std::vector<uint8_t> raw;
    std::size_t row = static_cast<std::size_t>(width) * 4u;
    raw.reserve((row + 1) * static_cast<std::size_t>(height));
    for (int y = 0; y < height; ++y) {
        raw.push_back(0);  // PNG filter type None
        const uint8_t* src = rgba + static_cast<std::size_t>(y) * row;
        raw.insert(raw.end(), src, src + row);
    }
    return raw;
}

// Wrap `raw` in a zlib stream whose DEFLATE body is a sequence of stored
// (type 0) blocks. The last block has BFINAL=1. Each block header is one
// byte: bit 0 = BFINAL, bits 1-2 = BTYPE (00 for stored). After the
// header, LEN (little-endian) and NLEN (one's complement) frame LEN
// bytes of literal data. Max LEN is 65535 per block.
std::vector<uint8_t> deflate_stored(const uint8_t* raw, std::size_t n) {
    std::vector<uint8_t> out;
    // zlib header: CMF=0x78 (deflate, 32 KB window), FLG=0x01 (no preset
    // dict, FCHECK makes 0x7801 % 31 == 0).
    out.push_back(0x78);
    out.push_back(0x01);
    std::size_t off = 0;
    bool any = false;
    while (off < n) {
        std::size_t chunk = n - off;
        if (chunk > kStoredBlockMax) chunk = kStoredBlockMax;
        bool last = (off + chunk == n);
        out.push_back(last ? 0x01 : 0x00);
        put_u16_le(out, static_cast<uint16_t>(chunk));
        put_u16_le(out, static_cast<uint16_t>(~static_cast<uint16_t>(chunk)));
        out.insert(out.end(), raw + off, raw + off + chunk);
        off += chunk;
        any = true;
    }
    // n == 0 is nonsensical for a PNG, but handle it correctly: emit one
    // empty stored block marked final so the zlib stream decodes.
    if (!any) {
        out.push_back(0x01);
        put_u16_le(out, 0);
        put_u16_le(out, 0xFFFF);
    }
    uint32_t ad = adler32(raw, n);
    put_u32_be(out, ad);
    return out;
}

}  // namespace

bool encode_rgba(const uint8_t* rgba, int width, int height,
                 std::vector<uint8_t>& out) {
    if (!rgba || width <= 0 || height <= 0) return false;

    // PNG signature.
    static const uint8_t kSig[8] = {0x89, 0x50, 0x4E, 0x47,
                                    0x0D, 0x0A, 0x1A, 0x0A};
    out.insert(out.end(), kSig, kSig + 8);

    // IHDR: width, height, bit depth 8, colour type 6 (RGBA), compression
    // 0, filter 0, interlace 0.
    uint8_t ihdr[13];
    uint32_t w = static_cast<uint32_t>(width);
    uint32_t h = static_cast<uint32_t>(height);
    ihdr[0]  = static_cast<uint8_t>((w >> 24) & 0xFF);
    ihdr[1]  = static_cast<uint8_t>((w >> 16) & 0xFF);
    ihdr[2]  = static_cast<uint8_t>((w >>  8) & 0xFF);
    ihdr[3]  = static_cast<uint8_t>( w        & 0xFF);
    ihdr[4]  = static_cast<uint8_t>((h >> 24) & 0xFF);
    ihdr[5]  = static_cast<uint8_t>((h >> 16) & 0xFF);
    ihdr[6]  = static_cast<uint8_t>((h >>  8) & 0xFF);
    ihdr[7]  = static_cast<uint8_t>( h        & 0xFF);
    ihdr[8]  = 8;
    ihdr[9]  = 6;
    ihdr[10] = 0;
    ihdr[11] = 0;
    ihdr[12] = 0;
    write_chunk(out, "IHDR", ihdr, sizeof(ihdr));

    std::vector<uint8_t> raw = build_scanlines(rgba, width, height);
    std::vector<uint8_t> zdata = deflate_stored(raw.data(), raw.size());
    write_chunk(out, "IDAT", zdata.data(), zdata.size());
    write_chunk(out, "IEND", nullptr, 0);
    return true;
}

}  // namespace thorbridge::png
