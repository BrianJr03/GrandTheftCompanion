// Host test for common/keys_v.cpp and the batched common/keys_search
// pass that keys_v relies on.
//
// We can't synthesise retail GTA5.exe blobs that happen to SHA-1 to
// the published hashes (that would be a preimage attack on SHA-1).
// So end-to-end coverage of locate_all against the real retail exe
// lands on-device at Gate V-1. The host side covers:
//
//   * find_blobs batched search — right offset returned per injected
//     blob, unmatched slots staying SIZE_MAX, alignment respected,
//     plus a sanity check on the sort / binary-search path with
//     many targets.
//   * Published hash list — count matches handoff (1 + 101 + 272 + 1
//     = 375), every string parses as valid 40-char hex, every digest
//     is unique.
//   * locate_all handles corner cases (null exe, too-small exe) and
//     refuses to publish when the retail blobs are absent.

#include "../keys_search.h"
#include "../keys_v.h"
#include "../sha1.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
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

// Deterministic xorshift PRNG so test byte streams are reproducible.
struct Rng {
    std::uint32_t s;
    explicit Rng(std::uint32_t seed) : s(seed ? seed : 1u) {}
    std::uint32_t next() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return s;
    }
    std::uint8_t next_byte() { return static_cast<std::uint8_t>(next() & 0xffu); }
};

void test_find_blobs_batched_round_trip() {
    constexpr std::size_t kN       = 32;
    constexpr std::size_t kBlob    = 64;
    constexpr std::size_t kExeSize = 256 * 1024;

    std::vector<std::uint8_t> exe(kExeSize);
    Rng r(0xC0DE);
    for (std::size_t i = 0; i < kExeSize; ++i) exe[i] = r.next_byte();

    // Place N distinct blobs at known DWORD-aligned offsets, well
    // apart so the random fill around them can't collide. Each blob
    // gets unique content so its SHA-1 is distinct.
    std::size_t placements[kN];
    for (std::size_t i = 0; i < kN; ++i) {
        placements[i] = 0x1000 + i * (kBlob + 0x20);
        for (std::size_t j = 0; j < kBlob; ++j) {
            exe[placements[i] + j] = static_cast<std::uint8_t>((i * 211 + j * 7 + 0x80) & 0xffu);
        }
    }

    std::vector<std::uint8_t> digests(kN * thorbridge::sha1::kDigestBytes);
    for (std::size_t i = 0; i < kN; ++i) {
        thorbridge::sha1::hash(exe.data() + placements[i], kBlob,
                               digests.data() + i * thorbridge::sha1::kDigestBytes);
    }

    std::size_t offs[kN];
    std::size_t matched = thorbridge::keys::find_blobs(
        exe.data(), kExeSize, kBlob, digests.data(), kN, offs);
    CHECK(matched == kN, "find_blobs matched every injected target");
    bool all_right = true;
    for (std::size_t i = 0; i < kN; ++i) {
        if (offs[i] != placements[i]) all_right = false;
    }
    CHECK(all_right, "find_blobs returned the exact injected offset per slot");
}

void test_find_blobs_partial_matches() {
    constexpr std::size_t kN       = 8;
    constexpr std::size_t kBlob    = 32;
    constexpr std::size_t kExeSize = 32 * 1024;
    std::vector<std::uint8_t> exe(kExeSize);
    Rng r(0xBEEF);
    for (std::size_t i = 0; i < kExeSize; ++i) exe[i] = r.next_byte();

    // Place the first N/2 blobs in the exe. The second half's digests
    // get fabricated so no slice in the exe can possibly match them.
    std::size_t placements[kN];
    for (std::size_t i = 0; i < kN / 2; ++i) {
        placements[i] = 0x200 + i * 0x100;
        for (std::size_t j = 0; j < kBlob; ++j) {
            exe[placements[i] + j] = static_cast<std::uint8_t>((i * 31 + j) & 0xffu);
        }
    }
    for (std::size_t i = kN / 2; i < kN; ++i) placements[i] = SIZE_MAX;

    std::vector<std::uint8_t> digests(kN * thorbridge::sha1::kDigestBytes);
    for (std::size_t i = 0; i < kN / 2; ++i) {
        thorbridge::sha1::hash(exe.data() + placements[i], kBlob,
                               digests.data() + i * thorbridge::sha1::kDigestBytes);
    }
    for (std::size_t i = kN / 2; i < kN; ++i) {
        // Fabricated digest that is almost certainly not any slice's SHA-1.
        for (std::size_t j = 0; j < thorbridge::sha1::kDigestBytes; ++j) {
            digests[i * thorbridge::sha1::kDigestBytes + j] =
                static_cast<std::uint8_t>((i * 97 + j * 13 + 0x11) & 0xffu);
        }
    }

    std::size_t offs[kN];
    std::size_t matched = thorbridge::keys::find_blobs(
        exe.data(), kExeSize, kBlob, digests.data(), kN, offs);
    CHECK(matched == kN / 2,
          "find_blobs reports only the subset of targets it actually found");
    bool first_half_ok = true, second_half_ok = true;
    for (std::size_t i = 0; i < kN / 2; ++i) {
        if (offs[i] != placements[i]) first_half_ok = false;
    }
    for (std::size_t i = kN / 2; i < kN; ++i) {
        if (offs[i] != SIZE_MAX) second_half_ok = false;
    }
    CHECK(first_half_ok, "find_blobs matched slots keep their injected offset");
    CHECK(second_half_ok, "find_blobs unmatched slots stay SIZE_MAX");
}

void test_find_blobs_dword_alignment() {
    // Blobs that live at non-DWORD-aligned offsets must NOT be found,
    // mirroring CodeWalker's alignment contract. The retail exe
    // ships every key material we care about on a 4-byte boundary.
    constexpr std::size_t kBlob    = 48;
    constexpr std::size_t kExeSize = 8 * 1024;
    std::vector<std::uint8_t> exe(kExeSize);
    Rng r(0xABCD);
    for (std::size_t i = 0; i < kExeSize; ++i) exe[i] = r.next_byte();

    constexpr std::size_t unaligned_off = 0x201;  // deliberate +1 offset
    for (std::size_t j = 0; j < kBlob; ++j) {
        exe[unaligned_off + j] = static_cast<std::uint8_t>((j * 11 + 0x40) & 0xffu);
    }
    std::uint8_t digest[thorbridge::sha1::kDigestBytes];
    thorbridge::sha1::hash(exe.data() + unaligned_off, kBlob, digest);

    std::size_t off;
    std::size_t matched = thorbridge::keys::find_blobs(
        exe.data(), kExeSize, kBlob, digest, 1, &off);
    CHECK(matched == 0, "find_blobs skips a blob placed at a non-DWORD offset");
    CHECK(off == SIZE_MAX, "find_blobs leaves the slot SIZE_MAX for an aligned-miss");
}

void test_published_hashes_parse() {
    using namespace thorbridge;
    std::uint8_t d[sha1::kDigestBytes];

    CHECK(keys::parse_hex(keys_v::kAesKeySha1Hex, d),
          "published AES key SHA-1 parses as 40 hex chars");
    CHECK(keys::parse_hex(keys_v::kLutSha1Hex, d),
          "published LUT SHA-1 parses as 40 hex chars");
    for (std::size_t i = 0; i < keys_v::kNgKeyCount; ++i) {
        bool ok = keys::parse_hex(keys_v::kNgKeySha1Hex[i], d);
        if (!ok) {
            std::fprintf(stderr, "  (ng_key[%zu] = '%s')\n", i,
                         keys_v::kNgKeySha1Hex[i]);
        }
        CHECK(ok, "every published NG-key SHA-1 parses");
    }
    for (std::size_t i = 0; i < keys_v::kNgTableCount; ++i) {
        bool ok = keys::parse_hex(keys_v::kNgTableSha1Hex[i], d);
        if (!ok) {
            std::fprintf(stderr, "  (ng_table[%zu] = '%s')\n", i,
                         keys_v::kNgTableSha1Hex[i]);
        }
        CHECK(ok, "every published NG-table SHA-1 parses");
    }
}

void test_published_hash_shapes() {
    // NG keys must all be distinct (the 101 slots are a key pool).
    using namespace thorbridge;
    std::set<std::string> ng_keys_seen;
    for (std::size_t i = 0; i < keys_v::kNgKeyCount; ++i) {
        ng_keys_seen.insert(keys_v::kNgKeySha1Hex[i]);
    }
    CHECK(ng_keys_seen.size() == keys_v::kNgKeyCount,
          "all 101 NG-key SHA-1 fingerprints are distinct");

    // NG decrypt tables intentionally duplicate: many (round, position)
    // slots share the same 1024-byte table in the retail layout. The
    // transcription from CodeWalker's GTAKeys.cs sees 84 distinct
    // tables across 272 slots; pin that count so a future
    // single-character transcription typo would move the number and
    // be caught.
    std::set<std::string> ng_tables_seen;
    for (std::size_t i = 0; i < keys_v::kNgTableCount; ++i) {
        ng_tables_seen.insert(keys_v::kNgTableSha1Hex[i]);
    }
    constexpr std::size_t kExpectedDistinctTables = 84;
    if (ng_tables_seen.size() != kExpectedDistinctTables) {
        std::fprintf(stderr, "  (got %zu distinct NG tables, expected %zu)\n",
                     ng_tables_seen.size(), kExpectedDistinctTables);
    }
    CHECK(ng_tables_seen.size() == kExpectedDistinctTables,
          "272 NG table slots collapse to exactly 84 distinct SHA-1s "
          "per CodeWalker's GTAKeys.cs");
}

void test_published_hash_counts_match_handoff() {
    CHECK(thorbridge::keys_v::kNgKeyCount == 101,
          "101 NG keys matches the retail layout documented in the handoff");
    CHECK(thorbridge::keys_v::kNgTableCount == 272,
          "272 NG decrypt tables (17 rounds × 16 positions) matches the handoff");
    CHECK(thorbridge::keys_v::kAesKeyBytes == 32,
          "AES key is 32 bytes (AES-256)");
    CHECK(thorbridge::keys_v::kNgKeyBytes == 272,
          "NG key is 272 bytes (17 round-subkeys of 16 bytes)");
    CHECK(thorbridge::keys_v::kNgTableBytes == 1024,
          "NG decrypt table is 1024 bytes (256 entries × uint32)");
    CHECK(thorbridge::keys_v::kLutBytes == 256,
          "LUT is 256 bytes");
    // Sanity: ExtractedKeys sized ~306,288 bytes. Allow a small
    // padding budget around the exact field sum so a struct re-order
    // with trailing padding does not trip the test, but a wrong-size
    // array declaration would.
    constexpr std::size_t kFieldsSum =
        thorbridge::keys_v::kAesKeyBytes +
        thorbridge::keys_v::kNgKeyCount * thorbridge::keys_v::kNgKeyBytes +
        thorbridge::keys_v::kNgTableCount * thorbridge::keys_v::kNgTableBytes +
        thorbridge::keys_v::kLutBytes;
    CHECK(thorbridge::keys_v::kExtractedKeysBytes >= kFieldsSum,
          "ExtractedKeys covers the sum of its advertised field bytes");
    CHECK(thorbridge::keys_v::kExtractedKeysBytes < kFieldsSum + 64,
          "ExtractedKeys has no runaway padding");
}

void test_locate_all_refuses_short_exe() {
    thorbridge::keys_v::ExtractedKeys out;
    std::uint8_t tiny[16] = {0};
    CHECK(!thorbridge::keys_v::locate_all(tiny, sizeof(tiny), out),
          "locate_all refuses an exe too small to hold even one blob");
    CHECK(!thorbridge::keys_v::locate_all(nullptr, 1 << 20, out),
          "locate_all refuses a null exe pointer");
}

void test_locate_all_refuses_random_exe() {
    // An exe filled with pseudo-random bytes should not contain any
    // slice whose SHA-1 matches the published fingerprints. (The odds
    // of a false positive are 2^-160 per candidate; we're fine.)
    constexpr std::size_t kExeSize = 2 * 1024 * 1024;  // ~2 MB
    std::vector<std::uint8_t> exe(kExeSize);
    Rng r(0xFEEDFACE);
    for (std::size_t i = 0; i < kExeSize; ++i) exe[i] = r.next_byte();
    auto out = std::make_unique<thorbridge::keys_v::ExtractedKeys>();
    bool ok = thorbridge::keys_v::locate_all(exe.data(), kExeSize, *out);
    CHECK(!ok,
          "locate_all refuses to publish an ExtractedKeys when no retail "
          "blobs are present in the exe");
}

}  // namespace

int main() {
    test_find_blobs_batched_round_trip();
    test_find_blobs_partial_matches();
    test_find_blobs_dword_alignment();
    test_published_hashes_parse();
    test_published_hash_shapes();
    test_published_hash_counts_match_handoff();
    test_locate_all_refuses_short_exe();
    test_locate_all_refuses_random_exe();
    if (failures) {
        std::fprintf(stderr, "\n%d keys_v check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all keys_v checks passed.\n");
    return 0;
}
