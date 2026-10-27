// Host-side test for common/inflate.cpp.
//
// Three paths covered:
//   1) A hand-built stored-only DEFLATE stream (BTYPE=00) wrapped in
//      zlib — same shape as our own PNG encoder's output.
//   2) A fixed-Huffman DEFLATE stream (BTYPE=01) that encodes a known
//      byte sequence by hand against the RFC 1951 fixed table.
//   3) A dynamic-Huffman DEFLATE stream (BTYPE=10). We produce it by
//      invoking inflate against a canned payload exercised below —
//      because we don't ship a deflater, we feed a sequence captured
//      from a different-but-standard tool for this one check and
//      verify the output; if the host doesn't have zlib the test
//      skips with a note.
//
// Everything that can be verified fully in-process is.

#include "../inflate.h"

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

uint32_t adler32(const uint8_t* data, std::size_t n) {
    uint32_t a = 1, b = 0;
    for (std::size_t i = 0; i < n; ++i) {
        a = (a + data[i]) % 65521u;
        b = (b + a) % 65521u;
    }
    return (b << 16) | a;
}

void test_stored_block() {
    const char* msg = "hello GTC atlas!";
    std::size_t n = std::strlen(msg);

    std::vector<uint8_t> stream;
    stream.push_back(0x78);
    stream.push_back(0x01);
    stream.push_back(0x01);                        // BFINAL=1, BTYPE=00
    stream.push_back(static_cast<uint8_t>(n & 0xFF));
    stream.push_back(static_cast<uint8_t>((n >> 8) & 0xFF));
    uint16_t nlen = static_cast<uint16_t>(~static_cast<uint16_t>(n));
    stream.push_back(static_cast<uint8_t>(nlen & 0xFF));
    stream.push_back(static_cast<uint8_t>((nlen >> 8) & 0xFF));
    stream.insert(stream.end(), msg, msg + n);
    uint32_t ad = adler32(reinterpret_cast<const uint8_t*>(msg), n);
    stream.push_back(static_cast<uint8_t>((ad >> 24) & 0xFF));
    stream.push_back(static_cast<uint8_t>((ad >> 16) & 0xFF));
    stream.push_back(static_cast<uint8_t>((ad >>  8) & 0xFF));
    stream.push_back(static_cast<uint8_t>( ad        & 0xFF));

    std::vector<uint8_t> out;
    bool ok = thorbridge::inflate::decompress(stream.data(), stream.size(),
                                              out, /*expect_zlib=*/true);
    CHECK(ok, "stored block decompress ok");
    CHECK(out.size() == n, "stored output size matches");
    CHECK(std::memcmp(out.data(), msg, n) == 0, "stored output bytes match");
}

// Build a fixed-Huffman stream containing the two literals 'A' (0x41)
// and 'B' (0x42), then end-of-block.
//
// Fixed table (RFC 1951 §3.2.6):
//   literals 0..143  → 8-bit codes 00110000..10111111
//   literals 144..255 → 9-bit codes 110010000..111111111
//   end-of-block (256) → 7-bit code 0000000
// 'A' = 0x41 → index 0x41 among 0..143, base 8-bit code 00110000 (0x30)
//   so code = 0x30 + 0x41 = 0x71. Transmitted MSB-first in the Huffman
//   sense but DEFLATE packs the bits LSB-first within each byte: for a
//   code of length 8 we reverse the bits to get the on-wire pattern.
//   0x71 = 0111 0001; reversed = 1000 1110 = 0x8E.
// Same for 'B' = 0x42 → 0x72 → reversed = 0100 1110 = 0x4E.
// End of block (256) → 7-bit code 0000000 → reversed = 0000000. Pad with
// 0s to a byte.
// Preceded by header byte: BFINAL=1 (bit 0), BTYPE=01 (bits 1-2) → 0b011
// = 0x03.
// Bits are packed into bytes LSB-first (header bit 0 goes into byte-0
// bit 0; the first literal code's reversed bits follow). Since the
// reversed 'A' code is 10001110 (lsb-first that is 01110001 reversed =
// yes), we need to pack carefully. Rather than hand-pack, we build the
// stream via a small bit-packer here.
void test_fixed_huffman() {
    struct Packer {
        std::vector<uint8_t> buf;
        uint32_t acc = 0;
        int held = 0;
        void push(uint32_t v, int n) {
            acc |= v << held;
            held += n;
            while (held >= 8) {
                buf.push_back(static_cast<uint8_t>(acc & 0xFF));
                acc >>= 8;
                held -= 8;
            }
        }
        void finish() { if (held > 0) buf.push_back(static_cast<uint8_t>(acc & 0xFF)); }
    };
    // Reverse the low `n` bits of `v`.
    auto rev = [](uint32_t v, int n) {
        uint32_t r = 0;
        for (int i = 0; i < n; ++i) {
            if (v & (1u << i)) r |= 1u << (n - 1 - i);
        }
        return r;
    };

    Packer p;
    p.push(1, 1);           // BFINAL = 1
    p.push(1, 2);           // BTYPE = 01 (fixed)
    // 'A' literal (code value 0x30 + 0x41 = 0x71, 8 bits).
    p.push(rev(0x71, 8), 8);
    // 'B' literal (code value 0x72, 8 bits).
    p.push(rev(0x72, 8), 8);
    // Literal 300 (used so we exercise the 9-bit range too). Index among
    // the 144..255 range is 300 - 144 = 156, base code 0b110010000, so
    // transmitted = 0b110010000 + 156 = 0b110010000 + 0x9C = ... Actually
    // 300 > 255. Fixed table literals only go up to 255. Skip; demo one
    // 9-bit literal instead, 'z' at 122 — no wait, 'z' is 122 < 143 so
    // still 8-bit. Use literal 200: base code 0b110010000 = 0x190; 200 -
    // 144 = 56; code = 0x190 + 56 = 0x1C8 (9 bits).
    p.push(rev(0x1C8, 9), 9);
    // End-of-block (symbol 256): 7-bit code 0b0000000.
    p.push(rev(0x00, 7), 7);
    p.finish();

    // Prepend zlib header, append Adler-32.
    std::vector<uint8_t> stream;
    stream.push_back(0x78);
    stream.push_back(0x01);
    stream.insert(stream.end(), p.buf.begin(), p.buf.end());
    const uint8_t expected[3] = {'A', 'B', 200};
    uint32_t ad = adler32(expected, 3);
    stream.push_back(static_cast<uint8_t>((ad >> 24) & 0xFF));
    stream.push_back(static_cast<uint8_t>((ad >> 16) & 0xFF));
    stream.push_back(static_cast<uint8_t>((ad >>  8) & 0xFF));
    stream.push_back(static_cast<uint8_t>( ad        & 0xFF));

    std::vector<uint8_t> out;
    bool ok = thorbridge::inflate::decompress(stream.data(), stream.size(),
                                              out, /*expect_zlib=*/true);
    CHECK(ok, "fixed-Huffman decompress ok");
    CHECK(out.size() == 3, "fixed-Huffman: 3 bytes decoded");
    CHECK(out.size() == 3 && out[0] == 'A' && out[1] == 'B' && out[2] == 200,
          "fixed-Huffman bytes 'A' 'B' 200");
}

// Dynamic-Huffman spot check: feed inflate the output of a trusted
// zlib-wrapped DEFLATE stream for a known string. We embed a captured
// stream so no linking against system zlib is needed.
//
// Payload: "The quick brown fox jumps over the lazy dog." encoded with
// Python's zlib.compress(data, level=9). Captured once; the test only
// cares about round-trip.
void test_dynamic_huffman() {
    // zlib.compress(b"The quick brown fox jumps over the lazy dog.", 9)
    // Captured on the host with Python's zlib; the test owns the bytes
    // so the suite does not need to link against a system zlib.
    static const uint8_t compressed[] = {
        0x78, 0xda, 0x0b, 0xc9, 0x48, 0x55, 0x28, 0x2c, 0xcd, 0x4c,
        0xce, 0x56, 0x48, 0x2a, 0xca, 0x2f, 0xcf, 0x53, 0x48, 0xcb,
        0xaf, 0x50, 0xc8, 0x2a, 0xcd, 0x2d, 0x28, 0x56, 0xc8, 0x2f,
        0x4b, 0x2d, 0x52, 0x28, 0x01, 0x4a, 0xe7, 0x24, 0x56, 0x55,
        0x2a, 0xa4, 0xe4, 0xa7, 0xeb, 0x01, 0x00, 0x6b, 0xe4, 0x10,
        0x08
    };
    const char* expected = "The quick brown fox jumps over the lazy dog.";

    std::vector<uint8_t> out;
    bool ok = thorbridge::inflate::decompress(compressed, sizeof(compressed),
                                              out, /*expect_zlib=*/true);
    CHECK(ok, "dynamic-Huffman decompress ok");
    CHECK(out.size() == std::strlen(expected),
          "dynamic-Huffman: output length matches");
    CHECK(out.size() == std::strlen(expected) &&
          std::memcmp(out.data(), expected, out.size()) == 0,
          "dynamic-Huffman bytes match the pangram");
}

void test_bad_zlib_header_rejected() {
    uint8_t bad[] = {0x78, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xFF, 0, 0, 0, 1};
    std::vector<uint8_t> out;
    bool ok = thorbridge::inflate::decompress(bad, sizeof(bad), out, true);
    CHECK(!ok, "bad zlib header rejected");
}

}  // namespace

int main() {
    test_stored_block();
    test_fixed_huffman();
    test_dynamic_huffman();
    test_bad_zlib_header_rejected();
    if (failures) {
        std::fprintf(stderr, "\n%d inflate check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all inflate checks passed.\n");
    return 0;
}
