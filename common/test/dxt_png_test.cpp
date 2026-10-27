// Host-side test for common/dxt.cpp + common/png.cpp.
//
// Three checks:
//   1) A hand-built DXT1 block decodes to the exact colours the spec
//      prescribes (red endpoint, blue endpoint, two interpolated).
//   2) A hand-built DXT5 block pairs an alpha gradient with a DXT1
//      colour block; selected pixels match the formula.
//   3) encode_rgba produces a PNG whose chunk layout parses and whose
//      declared width/height matches the input, and whose zlib stream
//      (stored-only) can be re-read and compared byte-for-byte against
//      the original scanlines.

#include "../dxt.h"
#include "../png.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond, msg)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            ++failures;                                                    \
            std::fprintf(stderr, "FAIL: %s (%s:%d)\n", msg, __FILE__,      \
                         __LINE__);                                        \
        }                                                                  \
    } while (0)

// ---- helpers for packing test blocks -----------------------------------

uint16_t pack565(int r, int g, int b) {
    return static_cast<uint16_t>(((r & 0x1F) << 11) | ((g & 0x3F) << 5) |
                                  (b & 0x1F));
}

void test_bc1_four_colour() {
    // Endpoint 0: pure red  (R=31,G=0,B=0)       → unpacks to 0xFF 0x00 0x00
    // Endpoint 1: pure blue (R=0, G=0, B=31)     → unpacks to 0x00 0x00 0xFF
    uint16_t c0 = pack565(31, 0, 0);
    uint16_t c1 = pack565(0, 0, 31);
    uint8_t block[8];
    block[0] = static_cast<uint8_t>(c0 & 0xFF);
    block[1] = static_cast<uint8_t>(c0 >> 8);
    block[2] = static_cast<uint8_t>(c1 & 0xFF);
    block[3] = static_cast<uint8_t>(c1 >> 8);
    // Index map, 16 pixels left→right / top→bottom:
    // row 0: 0 0 0 0  (red)
    // row 1: 1 1 1 1  (blue)
    // row 2: 2 2 2 2  (2*c0 + c1)/3
    // row 3: 3 3 3 3  (c0 + 2*c1)/3
    block[4] = 0b00000000;  // row 0
    block[5] = 0b01010101;  // row 1 (idx 1 four times)
    block[6] = 0b10101010;  // row 2 (idx 2 four times)
    block[7] = 0b11111111;  // row 3

    uint8_t rgba[4 * 4 * 4];
    bool ok = thorbridge::dxt::decode(
        thorbridge::dxt::Format::BC1, block, sizeof(block), 4, 4, rgba);
    CHECK(ok, "BC1 4-colour decode returns true");

    auto px = [&](int x, int y) -> const uint8_t* {
        return rgba + (y * 4 + x) * 4;
    };
    CHECK(px(0, 0)[0] == 0xFF && px(0, 0)[1] == 0x00 && px(0, 0)[2] == 0x00
          && px(0, 0)[3] == 0xFF, "row 0 is opaque red");
    CHECK(px(0, 1)[0] == 0x00 && px(0, 1)[2] == 0xFF
          && px(0, 1)[3] == 0xFF, "row 1 is opaque blue");
    // Row 2: (2*255 + 0)/3 = 170 in R; (0 + 2*0)/3 = 0 in B? No — the
    // interpolation is per channel of unpacked RGB888. Red endpoint R=0xFF
    // after replication (31 << 3 | 31 >> 2 = 248 | 7 = 255). (2*255 + 0)/3
    // = 170. Blue of blue endpoint = 255; (0 + 2*0)/3? No: wait, this is
    // (2*c0 + c1)/3 where c0 is red endpoint (0xFF,0,0) and c1 is blue
    // (0,0,0xFF). Red channel: (2*255 + 0)/3 = 170. Blue channel:
    // (2*0 + 255)/3 = 85.
    CHECK(px(0, 2)[0] == 170 && px(0, 2)[2] == 85,
          "row 2 interpolates (2c0+c1)/3 per channel");
    // Row 3: (c0 + 2*c1)/3. Red: 85, Blue: 170.
    CHECK(px(0, 3)[0] == 85 && px(0, 3)[2] == 170,
          "row 3 interpolates (c0+2c1)/3 per channel");
}

void test_bc1_three_colour_transparent() {
    // c0 <= c1 → 3-colour + transparent black at index 3.
    uint16_t c0 = pack565(0, 0, 0);
    uint16_t c1 = pack565(31, 0, 0);
    uint8_t block[8];
    block[0] = static_cast<uint8_t>(c0 & 0xFF);
    block[1] = static_cast<uint8_t>(c0 >> 8);
    block[2] = static_cast<uint8_t>(c1 & 0xFF);
    block[3] = static_cast<uint8_t>(c1 >> 8);
    // All index 3 across the block.
    block[4] = block[5] = block[6] = block[7] = 0xFF;

    uint8_t rgba[4 * 4 * 4];
    (void)thorbridge::dxt::decode(
        thorbridge::dxt::Format::BC1, block, sizeof(block), 4, 4, rgba);
    for (int i = 0; i < 16; ++i) {
        const uint8_t* p = rgba + i * 4;
        CHECK(p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 0,
              "BC1 3-colour mode: idx 3 → transparent black");
    }
}

void test_bc3_alpha_gradient() {
    uint8_t block[16];
    // Alpha block: a0=0x40, a1=0xC0; a0 < a1 → 6-interpolated + 0/255.
    block[0] = 0x40;
    block[1] = 0xC0;
    // 16 selectors × 3 bits = 48 bits. Place selector i for pixel i,
    // packed lsb-first across bytes 2..7. We write selectors 0..7
    // staircase then repeat for pixels 8..15.
    uint64_t sel = 0;
    for (int i = 0; i < 16; ++i) {
        uint64_t s = static_cast<uint64_t>(i & 7);
        sel |= s << (i * 3);
    }
    for (int i = 0; i < 6; ++i) {
        block[2 + i] = static_cast<uint8_t>((sel >> (i * 8)) & 0xFF);
    }
    // Colour block: red → blue, index 0 everywhere (so pixel 0 ends up
    // fully red with alpha 0x40, pixel 7 fully red with alpha 0xFF, etc).
    uint16_t c0 = pack565(31, 0, 0);
    uint16_t c1 = pack565(0, 0, 31);
    block[8]  = static_cast<uint8_t>(c0 & 0xFF);
    block[9]  = static_cast<uint8_t>(c0 >> 8);
    block[10] = static_cast<uint8_t>(c1 & 0xFF);
    block[11] = static_cast<uint8_t>(c1 >> 8);
    block[12] = block[13] = block[14] = block[15] = 0;

    uint8_t rgba[4 * 4 * 4];
    bool ok = thorbridge::dxt::decode(
        thorbridge::dxt::Format::BC3, block, sizeof(block), 4, 4, rgba);
    CHECK(ok, "BC3 decode returns true");

    auto a = [&](int i) { return rgba[i * 4 + 3]; };
    CHECK(a(0) == 0x40, "alpha selector 0 picks a0 (0x40)");
    CHECK(a(1) == 0xC0, "alpha selector 1 picks a1 (0xC0)");
    CHECK(a(6) == 0x00, "alpha selector 6 picks special 0 in a0<=a1 mode");
    CHECK(a(7) == 0xFF, "alpha selector 7 picks special 255 in a0<=a1 mode");
    // Pixel 2 uses interpolated alpha: (4*0x40 + 1*0xC0)/5 = 448/5 = 89
    // (integer division; matches the BC3 spec).
    CHECK(a(2) == 89, "alpha selector 2 interpolates (4*a0+1*a1)/5");

    // All pixels should be red (colour index 0 → c0 endpoint).
    for (int i = 0; i < 16; ++i) {
        CHECK(rgba[i * 4] == 0xFF && rgba[i * 4 + 2] == 0x00,
              "BC3 colour: idx 0 → red endpoint");
    }
}

// --- PNG round-trip -----------------------------------------------------

struct Chunk {
    char type[5];
    std::vector<uint8_t> data;
};

uint32_t rd32be(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) <<  8) |
            static_cast<uint32_t>(p[3]);
}

uint16_t rd16le(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

bool parse_png_chunks(const std::vector<uint8_t>& png,
                      std::vector<Chunk>* chunks) {
    static const uint8_t kSig[8] = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
    if (png.size() < 8 || std::memcmp(png.data(), kSig, 8) != 0) return false;
    std::size_t pos = 8;
    while (pos + 12 <= png.size()) {
        uint32_t len = rd32be(png.data() + pos);
        Chunk c;
        std::memcpy(c.type, png.data() + pos + 4, 4);
        c.type[4] = '\0';
        if (pos + 8 + len + 4 > png.size()) return false;
        c.data.assign(png.data() + pos + 8, png.data() + pos + 8 + len);
        chunks->push_back(c);
        pos += 8 + len + 4;
        if (std::strcmp(c.type, "IEND") == 0) return true;
    }
    return false;
}

// Un-wrap a stored-only zlib stream: validate header, walk stored blocks,
// append their literal payload, verify Adler-32 at the end.
bool stored_zlib_unwrap(const std::vector<uint8_t>& z,
                        std::vector<uint8_t>* raw) {
    if (z.size() < 6) return false;
    if (z[0] != 0x78 || z[1] != 0x01) return false;
    std::size_t p = 2;
    bool last = false;
    while (!last) {
        if (p + 5 > z.size()) return false;
        uint8_t hdr = z[p++];
        last = (hdr & 1) != 0;
        if ((hdr & 0x06) != 0x00) return false;  // BTYPE must be 0 (stored)
        uint16_t len = rd16le(z.data() + p); p += 2;
        uint16_t nlen = rd16le(z.data() + p); p += 2;
        if (static_cast<uint16_t>(~len) != nlen) return false;
        if (p + len > z.size()) return false;
        raw->insert(raw->end(), z.data() + p, z.data() + p + len);
        p += len;
    }
    if (p + 4 > z.size()) return false;
    uint32_t decl = rd32be(z.data() + p);
    uint32_t a = 1, b = 0;
    for (uint8_t x : *raw) {
        a = (a + x) % 65521u;
        b = (b + a) % 65521u;
    }
    uint32_t calc = (b << 16) | a;
    return calc == decl;
}

void test_png_round_trip() {
    const int W = 16, H = 7;
    std::vector<uint8_t> rgba(W * H * 4);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            uint8_t* p = rgba.data() + (y * W + x) * 4;
            p[0] = static_cast<uint8_t>(x * 16);
            p[1] = static_cast<uint8_t>(y * 32);
            p[2] = static_cast<uint8_t>((x + y) * 8);
            p[3] = 0xFF;
        }
    }

    std::vector<uint8_t> png;
    bool ok = thorbridge::png::encode_rgba(rgba.data(), W, H, png);
    CHECK(ok, "encode_rgba returns true");

    std::vector<Chunk> chunks;
    CHECK(parse_png_chunks(png, &chunks), "PNG chunk stream parses");
    CHECK(chunks.size() >= 3, "at least IHDR + IDAT + IEND");
    CHECK(std::strcmp(chunks.front().type, "IHDR") == 0, "first chunk IHDR");
    CHECK(std::strcmp(chunks.back().type, "IEND") == 0, "last chunk IEND");

    const auto& ihdr = chunks.front().data;
    CHECK(ihdr.size() == 13, "IHDR is 13 bytes");
    uint32_t w = rd32be(ihdr.data());
    uint32_t h = rd32be(ihdr.data() + 4);
    CHECK(static_cast<int>(w) == W, "IHDR width matches");
    CHECK(static_cast<int>(h) == H, "IHDR height matches");
    CHECK(ihdr[8] == 8 && ihdr[9] == 6, "IHDR: 8-bit RGBA");

    std::vector<uint8_t> idat;
    for (const auto& c : chunks) {
        if (std::strcmp(c.type, "IDAT") == 0) {
            idat.insert(idat.end(), c.data.begin(), c.data.end());
        }
    }
    std::vector<uint8_t> raw;
    CHECK(stored_zlib_unwrap(idat, &raw), "zlib stream unwraps + Adler ok");
    // raw should be (W*4 + 1) * H bytes: filter byte + scanline.
    CHECK(raw.size() == static_cast<std::size_t>((W * 4 + 1) * H),
          "unwrapped size = (row+1) * height");
    for (int y = 0; y < H; ++y) {
        const uint8_t* row = raw.data() + y * (W * 4 + 1);
        CHECK(row[0] == 0, "filter byte is None (0)");
        if (std::memcmp(row + 1, rgba.data() + y * W * 4,
                        W * 4) != 0) {
            CHECK(false, "scanline bytes match original rgba");
        }
    }
}

}  // namespace

int main() {
    test_bc1_four_colour();
    test_bc1_three_colour_transparent();
    test_bc3_alpha_gradient();
    test_png_round_trip();
    if (failures) {
        std::fprintf(stderr, "\n%d check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all dxt + png checks passed.\n");
    return 0;
}
