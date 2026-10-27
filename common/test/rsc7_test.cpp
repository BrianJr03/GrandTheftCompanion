// Host test for common/rsc7.cpp.
//
// Three areas of coverage:
//
//   * size_from_flags against the documented examples in Neodymium's
//     RpfFile.cs line 2362. Each example gives (ss, counters) and the
//     expected total memory size for one memory section; we rebuild
//     the flag word, feed it through, and compare.
//   * parse_header on a hand-built 16-byte RSC7 header: magic
//     accepted, mismatched magic refused, derived sizes populated,
//     short input refused.
//   * open() on a tiny RSC7 blob: header + stored-DEFLATE body
//     carrying a known plaintext. Round-trips to the plaintext. Also
//     test the CodeWalker fallback where a non-deflate body comes
//     back verbatim.

#include "../rsc7.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
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

// Compose a 32-bit flags word from the documented field layout. All
// counters are keyword-free positional; the parameter names below
// mirror the s0..s8/ss/type names used in CodeWalker's RpfFile.cs
// comment block so a reader can cross-reference the vectors against
// the published size examples line-for-line.
std::uint32_t make_flags(std::uint32_t ss,   std::uint32_t s0,
                         std::uint32_t s1,   std::uint32_t s2,
                         std::uint32_t s3,   std::uint32_t s4,
                         std::uint32_t s5,   std::uint32_t s6,
                         std::uint32_t s7,   std::uint32_t s8,
                         std::uint32_t type = 0) {
    return
        ((ss   & 0xFu)  <<  0) |
        ((s8   & 0x1u)  <<  4) |
        ((s7   & 0x3u)  <<  5) |
        ((s6   & 0xFu)  <<  7) |
        ((s5   & 0x3Fu) << 11) |
        ((s4   & 0x7Fu) << 17) |
        ((s3   & 0x1u)  << 24) |
        ((s2   & 0x1u)  << 25) |
        ((s1   & 0x1u)  << 26) |
        ((s0   & 0x1u)  << 27) |
        ((type & 0xFu)  << 28);
}

void push_u32(std::vector<std::uint8_t>& v, std::uint32_t x) {
    v.push_back(static_cast<std::uint8_t>( x        & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >>  8) & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 16) & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 24) & 0xff));
}

void push_u16(std::vector<std::uint8_t>& v, std::uint16_t x) {
    v.push_back(static_cast<std::uint8_t>(x & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 8) & 0xff));
}

// Build a BFINAL=1, BTYPE=00 (stored) raw-DEFLATE stream wrapping
// `payload`. Byte format per RFC 1951 §3.2.4:
//   first byte = 0x01       (BFINAL + BTYPE=00, padded to byte)
//   LEN  little-endian uint16
//   NLEN little-endian uint16 (= ~LEN)
//   LEN bytes of raw payload
std::vector<std::uint8_t> build_stored_deflate(const std::vector<std::uint8_t>& payload) {
    std::vector<std::uint8_t> out;
    out.push_back(0x01);
    push_u16(out, static_cast<std::uint16_t>(payload.size()));
    push_u16(out, static_cast<std::uint16_t>(~payload.size()));
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

void test_size_from_flags_vectors() {
    // Each vector is a documented (ss, counters) -> size tuple from
    // the comment block in RpfFile.cs line 2362. make_flags positional
    // args are: ss, s0, s1, s2, s3, s4, s5, s6, s7, s8, (type).
    struct Vec { const char* label; std::uint32_t flags; std::uint32_t expected; };
    const Vec vecs[] = {
        {"zero flags",      make_flags(0, 0,0,0,0, 0, 0, 0,0, 0), 0},
        {"ss:0 s4:1",       make_flags(0, 0,0,0,0, 1, 0, 0,0, 0), 8192},
        {"ss:0 s5:1",       make_flags(0, 0,0,0,0, 0, 1, 0,0, 0), 16384},
        {"ss:0 s4:1 s5:1",  make_flags(0, 0,0,0,0, 1, 1, 0,0, 0), 24576},
        {"ss:0 s4:1 s5:2",  make_flags(0, 0,0,0,0, 1, 2, 0,0, 0), 40960},
        {"ss:0 s4:2 s5:2",  make_flags(0, 0,0,0,0, 2, 2, 0,0, 0), 49152},
        {"ss:1 s6:9",       make_flags(1, 0,0,0,0, 0, 0, 9,0, 0), 589824},
        {"ss:4 s4:15 s7:2", make_flags(4, 0,0,0,0,15, 0, 0,2, 0), 4063232},
        {"ss:5 s4:13 s6:5", make_flags(5, 0,0,0,0,13, 0, 5,0, 0), 8650752},
    };
    for (const auto& v : vecs) {
        std::uint32_t got = thorbridge::rsc7::size_from_flags(v.flags);
        if (got != v.expected) {
            std::fprintf(stderr,
                "  (%s): flags=0x%08X -> %u, expected %u\n",
                v.label, v.flags, got, v.expected);
        }
        CHECK(got == v.expected,
              "size_from_flags matches CodeWalker's documented example");
    }
}

void test_parse_header_basic() {
    std::vector<std::uint8_t> blob;
    push_u32(blob, thorbridge::rsc7::kMagic);
    push_u32(blob, /*version=*/42u);
    push_u32(blob, /*system_flags=*/
             make_flags(0, 0,0,0,0, 1, 0, 0,0, 0, /*type=*/1));
    push_u32(blob, /*graphics_flags=*/
             make_flags(1, 0,0,0,0, 0, 0, 9,0, 0, /*type=*/2));

    thorbridge::rsc7::Header h{};
    CHECK(thorbridge::rsc7::parse_header(blob.data(), blob.size(), h),
          "parse_header accepts a valid RSC7 header");
    CHECK(h.version == 42u, "version uint32 preserved");
    CHECK(h.system_size   == 8192u,
          "system_size derived from system_flags matches size_from_flags");
    CHECK(h.graphics_size == 589824u,
          "graphics_size derived from graphics_flags matches size_from_flags");
    CHECK(h.system_type   == 1u, "system_type is top 4 bits of system_flags");
    CHECK(h.graphics_type == 2u, "graphics_type is top 4 bits of graphics_flags");
}

void test_parse_header_errors() {
    thorbridge::rsc7::Header h{};
    CHECK(!thorbridge::rsc7::parse_header(nullptr, 1024, h),
          "parse_header refuses a null pointer");
    std::uint8_t too_short[8] = {0};
    CHECK(!thorbridge::rsc7::parse_header(too_short, sizeof(too_short), h),
          "parse_header refuses an input shorter than 16 bytes");

    // 16 bytes with wrong magic.
    std::vector<std::uint8_t> blob;
    push_u32(blob, 0xDEADBEEFu);
    push_u32(blob, 0u);
    push_u32(blob, 0u);
    push_u32(blob, 0u);
    CHECK(!thorbridge::rsc7::parse_header(blob.data(), blob.size(), h),
          "parse_header refuses a non-RSC7 magic");
}

void test_open_round_trips_stored_deflate() {
    const std::string plaintext_s = "payload bytes for the RSC7 round trip";
    std::vector<std::uint8_t> plaintext(plaintext_s.begin(), plaintext_s.end());
    auto deflated = build_stored_deflate(plaintext);

    std::vector<std::uint8_t> blob;
    push_u32(blob, thorbridge::rsc7::kMagic);
    push_u32(blob, /*version=*/7u);
    push_u32(blob, make_flags(0, 0,0,0,0, 1, 0, 0,0, 0));
    push_u32(blob, 0u);
    blob.insert(blob.end(), deflated.begin(), deflated.end());

    thorbridge::rsc7::Header h{};
    std::vector<std::uint8_t> payload;
    CHECK(thorbridge::rsc7::open(blob.data(), blob.size(), h, payload),
          "open() parses header and decompresses a stored-deflate body");
    CHECK(h.version == 7u, "open() preserves the version");
    CHECK(payload.size() == plaintext.size() &&
          std::memcmp(payload.data(), plaintext.data(), plaintext.size()) == 0,
          "open() recovers the stored-deflate payload byte-for-byte");
}

void test_open_falls_back_on_bad_deflate() {
    // Body that does NOT parse as a valid deflate stream. The first
    // byte picks BTYPE=11 (reserved / error). CodeWalker's fallback
    // hands the body back verbatim; so should rsc7::open.
    std::vector<std::uint8_t> blob;
    push_u32(blob, thorbridge::rsc7::kMagic);
    push_u32(blob, 1u);
    push_u32(blob, 0u);
    push_u32(blob, 0u);
    const std::uint8_t body[] = {0x07, 0xff, 0xff, 0xff, 0xff};
    blob.insert(blob.end(), std::begin(body), std::end(body));

    thorbridge::rsc7::Header h{};
    std::vector<std::uint8_t> payload;
    CHECK(thorbridge::rsc7::open(blob.data(), blob.size(), h, payload),
          "open() still returns true when deflate fails (fallback path)");
    CHECK(payload.size() == sizeof(body) &&
          std::memcmp(payload.data(), body, sizeof(body)) == 0,
          "open() hands back the body verbatim when deflate decode fails");
}

void test_open_empty_body() {
    // Valid header, zero-length body. The payload stays empty.
    std::vector<std::uint8_t> blob;
    push_u32(blob, thorbridge::rsc7::kMagic);
    push_u32(blob, 3u);
    push_u32(blob, 0u);
    push_u32(blob, 0u);
    thorbridge::rsc7::Header h{};
    std::vector<std::uint8_t> payload{0xAA, 0xBB};  // start non-empty
    CHECK(thorbridge::rsc7::open(blob.data(), blob.size(), h, payload),
          "open() accepts a header-only resource");
    CHECK(payload.empty(),
          "open() leaves the payload empty for a header-only resource");
    CHECK(h.version == 3u, "open() still reads the version with no body");
}

}  // namespace

int main() {
    test_size_from_flags_vectors();
    test_parse_header_basic();
    test_parse_header_errors();
    test_open_round_trips_stored_deflate();
    test_open_falls_back_on_bad_deflate();
    test_open_empty_body();
    if (failures) {
        std::fprintf(stderr, "\n%d rsc7 check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all rsc7 checks passed.\n");
    return 0;
}
