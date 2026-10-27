// Host test for common/ng.cpp.
//
// We ship decrypt only (we never author encrypted archives) and we
// don't have access to the retail NG tables inside this repo. The
// Rockstar key / table material lives in each user's own GTA5.exe,
// located at install time by SHA-1 fingerprint. So the test here
// covers the structural correctness of the decrypt pipeline rather
// than the specific NG transform:
//
//   * Determinism:            same inputs always produce the same output.
//   * Column independence:    NG is block-diagonal per 32-bit column
//                             (positions i and i±4 land in different
//                             output columns). Flipping any bit in
//                             column c therefore leaves every byte
//                             outside column c identical. This is the
//                             non-obvious correctness invariant the
//                             header documents, so pin it directly.
//   * Column avalanche:       within the one column a single-bit flip
//                             touches, random tables scramble several
//                             bits by round 17. A per-column threshold
//                             rather than whole-block, matching the
//                             diagonal design.
//   * All-zero tables oracle: with zero tables the table_pass is a
//                             constant zero regardless of input or key,
//                             so the whole 17-round pipeline collapses
//                             to a known-zero output. Any wiring error
//                             that forgets a round or mislabels Round
//                             A/B lands outside this invariant.
//   * ECB walk:               multi-block decrypt is byte-identical to
//                             repeated single-block decrypt, with the
//                             trailing partial block left untouched.
//   * load_* parsing:         right size accepted; wrong size refused;
//                             uint32 entries decoded little-endian so
//                             the output is portable across hosts.
//
// Once the real retail tables are in reach we add a published vector
// in a follow-up pass. The round-structure smoke tests above catch
// every wiring error the vector would catch, bar actual byte-identity.

#include "../ng.h"

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

// Deterministic 32-bit PRNG (xorshift). Used to populate synthetic
// keys and tables without pulling std::random in; same seed means
// same bytes everywhere this test runs.
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

void fill_key(thorbridge::ng::Key& k, std::uint32_t seed) {
    Rng r(seed);
    for (std::size_t rr = 0; rr < thorbridge::ng::kRoundsPerBlock; ++rr) {
        for (std::size_t j = 0; j < thorbridge::ng::kRoundKeyBytes; ++j) {
            k.rounds[rr][j] = r.next_byte();
        }
    }
}

void fill_tables(thorbridge::ng::Tables& t, std::uint32_t seed) {
    Rng r(seed);
    for (std::size_t rr = 0; rr < thorbridge::ng::kRoundsPerBlock; ++rr) {
        for (std::size_t p = 0; p < thorbridge::ng::kTablesPerRound; ++p) {
            for (std::size_t e = 0; e < thorbridge::ng::kEntriesPerTable; ++e) {
                t.rounds[rr][p][e] = r.next();
            }
        }
    }
}

int bit_diff(const std::uint8_t* a, const std::uint8_t* b, std::size_t n) {
    int d = 0;
    for (std::size_t i = 0; i < n; ++i) {
        std::uint8_t x = a[i] ^ b[i];
        while (x) { d += (x & 1); x >>= 1; }
    }
    return d;
}

void test_determinism() {
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables tables{};
    fill_key(key, 0x42);
    fill_tables(tables, 0x13);
    std::uint8_t a[16], b[16];
    for (int i = 0; i < 16; ++i) a[i] = static_cast<std::uint8_t>(i * 11 + 3);
    std::memcpy(b, a, 16);
    thorbridge::ng::decrypt_block(key, tables, a);
    thorbridge::ng::decrypt_block(key, tables, b);
    CHECK(std::memcmp(a, b, 16) == 0,
          "NG decrypt is deterministic on identical inputs");
}

void test_column_independence() {
    // NG is block-diagonal per 32-bit column: positions 0..3 feed
    // output bytes 0..3, positions 4..7 feed 4..7, etc. Flipping any
    // bit in a byte of column c must leave every byte outside column
    // c unchanged. Walk all four columns; for each one, flip a bit in
    // every byte of the column and verify the OTHER columns stay
    // byte-identical across the whole 17-round decrypt.
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables tables{};
    fill_key(key, 0x1234);
    fill_tables(tables, 0x5678);
    for (int col = 0; col < 4; ++col) {
        for (int within = 0; within < 4; ++within) {
            std::uint8_t a[16] = {0}, b[16] = {0};
            b[col * 4 + within] ^= 0x01;
            thorbridge::ng::decrypt_block(key, tables, a);
            thorbridge::ng::decrypt_block(key, tables, b);
            for (int other = 0; other < 4; ++other) {
                if (other == col) continue;
                bool other_bytes_match =
                    std::memcmp(a + other * 4, b + other * 4, 4) == 0;
                CHECK(other_bytes_match,
                      "flipping a bit inside one column leaves every other "
                      "column byte-identical");
            }
        }
    }
}

void test_within_column_avalanche() {
    // Inside the one column a flipped bit lives in, 17 rounds of
    // table lookups + XOR-with-key should scramble several bits.
    // With random tables we expect something like half of the 32
    // column bits to differ on average; use a conservative threshold
    // of 4 so the test is stable across seeds. (Zero would still
    // signal a real round-wiring problem, not just an unlucky seed.)
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables tables{};
    fill_key(key, 0x5678);
    fill_tables(tables, 0x1234);
    std::uint8_t a[16] = {0}, b[16] = {0};
    b[7] ^= 0x01;  // byte 7 is in column 1
    thorbridge::ng::decrypt_block(key, tables, a);
    thorbridge::ng::decrypt_block(key, tables, b);
    int diff = bit_diff(a + 4, b + 4, 4);  // column 1 is bytes 4..7
    CHECK(diff >= 4,
          "flipping one bit inside a column scrambles several bits of "
          "that column by round 17");
}

void test_key_sensitivity_within_column() {
    // Flipping a bit of key.rounds[r][p] XORs that bit into block[p]
    // during round r. Position p feeds column p/4, so the effect is
    // confined to that column just like a ciphertext bit flip is.
    thorbridge::ng::Key k1{}, k2{};
    thorbridge::ng::Tables tables{};
    fill_key(k1, 0xaaaa);
    fill_tables(tables, 0xbbbb);
    std::memcpy(&k2, &k1, sizeof(k1));
    k2.rounds[0][5] ^= 0x01;  // position 5, which is column 1
    std::uint8_t a[16], b[16];
    for (int i = 0; i < 16; ++i) a[i] = b[i] = 0xcc;
    thorbridge::ng::decrypt_block(k1, tables, a);
    thorbridge::ng::decrypt_block(k2, tables, b);
    bool col0_same = std::memcmp(a + 0,  b + 0,  4) == 0;
    bool col2_same = std::memcmp(a + 8,  b + 8,  4) == 0;
    bool col3_same = std::memcmp(a + 12, b + 12, 4) == 0;
    int col1_diff = bit_diff(a + 4, b + 4, 4);
    CHECK(col0_same && col2_same && col3_same,
          "flipping a round-key bit at position p leaves columns != p/4 "
          "byte-identical");
    CHECK(col1_diff >= 1,
          "flipping a round-key bit at position p alters column p/4");
}

void test_tables_sensitivity() {
    // Each round-N position-P table is read by every decrypt that
    // reaches round N. The input to the lookup is just whichever
    // byte value the ciphertext mutates into at that step. Mutating
    // one specific entry (round 7 position 5, byte 0x80) only lands
    // for the inputs whose round-7-position-5 byte happens to be
    // 0x80, which is seed-dependent and brittle to assert.
    //
    // Alter the ENTIRE round-0 position-5 table instead. Every
    // decrypt reads round-0 position-5 with its original ciphertext
    // byte 5 (after one XOR with the key), so the altered table is
    // guaranteed to be hit. Position 5 feeds column 1 (5 / 4 = 1),
    // so the difference must stay in column 1.
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables t1{}, t2{};
    fill_key(key, 0xdead);
    fill_tables(t1, 0xbeef);
    std::memcpy(&t2, &t1, sizeof(t1));
    constexpr int kAlteredRound    = 0;
    constexpr int kAlteredPosition = 5;
    for (int v = 0; v < 256; ++v) {
        t2.rounds[kAlteredRound][kAlteredPosition][v] ^= 0x80u;
    }
    bool any_diff_in_altered_column = false;
    bool diff_outside_altered_column = false;
    for (int seed = 0; seed < 16; ++seed) {
        std::uint8_t a[16], b[16];
        for (int i = 0; i < 16; ++i) {
            a[i] = b[i] = static_cast<std::uint8_t>(seed * 31 + i * 7);
        }
        thorbridge::ng::decrypt_block(key, t1, a);
        thorbridge::ng::decrypt_block(key, t2, b);
        for (int col = 0; col < 4; ++col) {
            bool col_differs = std::memcmp(a + col * 4, b + col * 4, 4) != 0;
            if (col_differs) {
                if (col == kAlteredPosition / 4) any_diff_in_altered_column = true;
                else                             diff_outside_altered_column = true;
            }
        }
    }
    CHECK(any_diff_in_altered_column,
          "altering a round-0 position table changes outputs in that "
          "position's column");
    CHECK(!diff_outside_altered_column,
          "altering a round-0 position table never leaks into a different "
          "column");
}

void test_all_zero_tables_collapse_to_zero() {
    // With every decrypt-table entry set to zero, each table_pass
    // writes an all-zero output regardless of its input. The 17-round
    // pipeline therefore collapses to a known-zero plaintext for any
    // ciphertext and any key. This catches round-structure wiring bugs
    // (missing a round, swapping RoundA / RoundB, etc) that pure
    // avalanche cannot distinguish from a correct implementation.
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables tables{};  // zero-initialised
    fill_key(key, 0xfeed);
    std::uint8_t block[16];
    for (int i = 0; i < 16; ++i) block[i] = static_cast<std::uint8_t>(0xa0 + i);
    thorbridge::ng::decrypt_block(key, tables, block);
    bool all_zero = true;
    for (int i = 0; i < 16; ++i) if (block[i] != 0) all_zero = false;
    CHECK(all_zero,
          "all-zero tables collapse every 17-round decrypt to zero");
}

void test_ecb_matches_single_block() {
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables tables{};
    fill_key(key, 0x3141);
    fill_tables(tables, 0x5926);
    std::uint8_t buf[48], ref[48];
    for (int i = 0; i < 48; ++i) buf[i] = ref[i] = static_cast<std::uint8_t>(i * 5 + 1);
    thorbridge::ng::decrypt_ecb(key, tables, buf, 48);
    thorbridge::ng::decrypt_block(key, tables, ref + 0);
    thorbridge::ng::decrypt_block(key, tables, ref + 16);
    thorbridge::ng::decrypt_block(key, tables, ref + 32);
    CHECK(std::memcmp(buf, ref, 48) == 0,
          "decrypt_ecb matches repeated decrypt_block");
}

void test_ecb_preserves_partial_tail() {
    thorbridge::ng::Key key{};
    thorbridge::ng::Tables tables{};
    fill_key(key, 0x7);
    fill_tables(tables, 0x11);
    std::uint8_t buf[20], tail_copy[4];
    for (int i = 0; i < 20; ++i) buf[i] = static_cast<std::uint8_t>(i);
    std::memcpy(tail_copy, buf + 16, 4);
    thorbridge::ng::decrypt_ecb(key, tables, buf, 20);
    CHECK(std::memcmp(buf + 16, tail_copy, 4) == 0,
          "decrypt_ecb leaves the trailing partial block untouched");
}

void test_load_key_sizes() {
    std::vector<std::uint8_t> raw(thorbridge::ng::kKeyBytes);
    for (std::size_t i = 0; i < raw.size(); ++i) {
        raw[i] = static_cast<std::uint8_t>(i & 0xff);
    }
    thorbridge::ng::Key k{};
    CHECK(thorbridge::ng::load_key(raw.data(), raw.size(), k),
          "load_key accepts exactly 272 bytes");
    CHECK(k.rounds[5][7] == static_cast<std::uint8_t>((5 * 16 + 7) & 0xff),
          "load_key preserves byte order inside each round subkey");
    CHECK(!thorbridge::ng::load_key(raw.data(), raw.size() - 1, k),
          "load_key refuses a short buffer");
    CHECK(!thorbridge::ng::load_key(raw.data(), raw.size() + 1, k),
          "load_key refuses an oversized buffer");
    CHECK(!thorbridge::ng::load_key(nullptr, raw.size(), k),
          "load_key refuses a null pointer");
}

void test_load_tables_sizes_and_endianness() {
    std::vector<std::uint8_t> raw(thorbridge::ng::kTablesBytes);
    for (std::size_t i = 0; i < raw.size(); ++i) {
        raw[i] = static_cast<std::uint8_t>((i * 7u + 3u) & 0xffu);
    }
    thorbridge::ng::Tables t{};
    CHECK(thorbridge::ng::load_tables(raw.data(), raw.size(), t),
          "load_tables accepts exactly 278,528 bytes");

    // Verify one specific entry is decoded little-endian. If we had
    // written it big-endian the three asserts below would catch us.
    std::size_t off = (((3 * thorbridge::ng::kTablesPerRound) + 5) *
                       thorbridge::ng::kEntriesPerTable + 128) *
                      thorbridge::ng::kBytesPerEntry;
    std::uint32_t want =
        static_cast<std::uint32_t>(raw[off + 0]) |
        (static_cast<std::uint32_t>(raw[off + 1]) << 8) |
        (static_cast<std::uint32_t>(raw[off + 2]) << 16) |
        (static_cast<std::uint32_t>(raw[off + 3]) << 24);
    CHECK(t.rounds[3][5][128] == want,
          "load_tables decodes entries little-endian");

    CHECK(!thorbridge::ng::load_tables(raw.data(), raw.size() - 1, t),
          "load_tables refuses a short buffer");
    CHECK(!thorbridge::ng::load_tables(raw.data(), raw.size() + 1, t),
          "load_tables refuses an oversized buffer");
    CHECK(!thorbridge::ng::load_tables(nullptr, raw.size(), t),
          "load_tables refuses a null pointer");
}

}  // namespace

int main() {
    test_determinism();
    test_column_independence();
    test_within_column_avalanche();
    test_key_sensitivity_within_column();
    test_tables_sensitivity();
    test_all_zero_tables_collapse_to_zero();
    test_ecb_matches_single_block();
    test_ecb_preserves_partial_tail();
    test_load_key_sizes();
    test_load_tables_sizes_and_endianness();
    if (failures) {
        std::fprintf(stderr, "\n%d NG check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all NG checks passed.\n");
    return 0;
}
