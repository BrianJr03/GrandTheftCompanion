// Host test for common/ng.cpp.
//
// The retail NG key / table material lives in each user's own
// GTA5.exe; we locate it at install time via SHA-1 fingerprint. For
// off-device testing we use synthetic key + tables and cross-check
// against a Python port of the same algorithm (see
// scripts/calib/*/ng_ref.py tracked out-of-tree if you need it).
// The algebraic properties pinned here cover every wiring error a
// published byte-for-byte vector would catch, plus a known-value
// smoke test using a synthetic key / table formula that matches the
// Python reference exactly.
//
//   * Determinism:            same inputs always give the same output.
//   * Zero oracle:            all-zero key + tables leaves any input
//                             at zero (every round outputs its
//                             round-subkey, which is 0, independent
//                             of input).
//   * Last-round oracle:      all-zero tables with an arbitrary key
//                             collapses any input to pack_LE(key[16]).
//                             This pins the round schedule: miss a
//                             round or mis-order RoundA/RoundB and
//                             the output changes.
//   * Known vector:           a specific input decrypted under a
//                             specific synthetic key + tables formula
//                             produces specific bytes. The expected
//                             bytes were computed by a Python port
//                             of CodeWalker's DecryptNGBlock.
//   * Avalanche:              a one-bit flip in block / key / tables
//                             cascades into many bits of the output
//                             across all 4 columns (RoundB mixes).
//   * ECB walk:               multi-block decrypt is byte-identical
//                             to repeated single-block decrypt with
//                             the trailing partial block preserved.
//   * load_* parsing:         right size accepted, wrong size refused,
//                             uint32 fields decoded little-endian.
//   * pick_file_key:          (hash + size + 61) % 101 math and
//                             pointer offset into the 101-key pool.

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

// The exact synthesis Python uses to generate the known-vector
// expected output: tables[r][p][v] = (r*37 + p*19 + v*7 + 1) * k1
// and key[r][c] = (r*47 + c*13 + 3) * k2, all mod 2^32. The two
// multipliers are large odd primes so the formula covers the full
// uint32 range densely.
constexpr std::uint32_t kSynthTableK = 2654435761u;
constexpr std::uint32_t kSynthKeyK   = 2246822519u;

void fill_synthetic(thorbridge::ng::Key& k, thorbridge::ng::Tables& t) {
    for (std::size_t r = 0; r < thorbridge::ng::kRoundsPerBlock; ++r) {
        for (std::size_t c = 0; c < thorbridge::ng::kRoundKeyUInts; ++c) {
            k.rounds[r][c] =
                (static_cast<std::uint32_t>(r * 47 + c * 13 + 3)) *
                kSynthKeyK;
        }
        for (std::size_t p = 0; p < thorbridge::ng::kTablesPerRound; ++p) {
            for (std::size_t v = 0; v < thorbridge::ng::kEntriesPerTable; ++v) {
                t.rounds[r][p][v] =
                    (static_cast<std::uint32_t>(r * 37 + p * 19 + v * 7 + 1)) *
                    kSynthTableK;
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
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k, t);
    std::uint8_t a[16], b[16];
    for (int i = 0; i < 16; ++i) a[i] = static_cast<std::uint8_t>(i * 11 + 3);
    std::memcpy(b, a, 16);
    thorbridge::ng::decrypt_block(k, t, a);
    thorbridge::ng::decrypt_block(k, t, b);
    CHECK(std::memcmp(a, b, 16) == 0,
          "NG decrypt is deterministic on identical inputs");
}

void test_zero_everything_yields_zero() {
    // Every round reads some table entries (= 0) and XORs with its
    // subkey (= 0). Output stays zero across all 17 rounds for ANY
    // input. If a round were skipped or if a table lookup were
    // accidentally XOR'd with the INPUT instead of just the tables,
    // this test fails.
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    std::uint8_t block[16];
    for (int i = 0; i < 16; ++i) block[i] = 0xaa;
    thorbridge::ng::decrypt_block(k, t, block);
    bool all_zero = true;
    for (int i = 0; i < 16; ++i) if (block[i] != 0) all_zero = false;
    CHECK(all_zero,
          "all-zero key + tables collapses any input to zero");
}

void test_zero_tables_yield_packed_last_key() {
    // With tables zero, each round's output per column = subkey[c].
    // The output overwrites the block so the next round's lookups
    // read the previous key. By round 16 (the final round) the
    // block equals pack_LE(key[16]).
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    for (std::size_t r = 0; r < thorbridge::ng::kRoundsPerBlock; ++r) {
        for (std::size_t c = 0; c < thorbridge::ng::kRoundKeyUInts; ++c) {
            k.rounds[r][c] =
                (static_cast<std::uint32_t>(r * 47 + c * 13 + 3)) * kSynthKeyK;
        }
    }
    std::uint8_t block[16];
    for (int i = 0; i < 16; ++i) block[i] = 0xaa;
    thorbridge::ng::decrypt_block(k, t, block);
    std::uint8_t expected[16];
    for (std::size_t c = 0; c < 4; ++c) {
        std::uint32_t u = k.rounds[16][c];
        expected[c * 4 + 0] = static_cast<std::uint8_t>( u        & 0xffu);
        expected[c * 4 + 1] = static_cast<std::uint8_t>((u >>  8) & 0xffu);
        expected[c * 4 + 2] = static_cast<std::uint8_t>((u >> 16) & 0xffu);
        expected[c * 4 + 3] = static_cast<std::uint8_t>((u >> 24) & 0xffu);
    }
    CHECK(std::memcmp(block, expected, 16) == 0,
          "zero tables + arbitrary key collapses any input to pack_LE(key[16])");
}

void test_known_vector_sequential_input() {
    // Decrypting 00..0F under the synthetic (key, tables) formula.
    // Expected bytes computed by a Python port of CodeWalker's
    // DecryptNGBlock against the same formula.
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k, t);
    std::uint8_t block[16];
    for (int i = 0; i < 16; ++i) block[i] = static_cast<std::uint8_t>(i);
    const std::uint8_t expected[16] = {
        0x80, 0xBA, 0x4E, 0x20, 0x25, 0x90, 0x72, 0xF2,
        0x98, 0x53, 0xAD, 0x07, 0xD1, 0x99, 0x32, 0xC8,
    };
    thorbridge::ng::decrypt_block(k, t, block);
    CHECK(std::memcmp(block, expected, 16) == 0,
          "known-vector decrypt of 00..0F matches the Python reference");
}

void test_known_vector_zero_input() {
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k, t);
    std::uint8_t block[16] = {0};
    const std::uint8_t expected[16] = {
        0x0F, 0x97, 0x21, 0xB1, 0x19, 0xEA, 0x6E, 0xB6,
        0xCB, 0x80, 0x13, 0xA6, 0xE4, 0x96, 0x6B, 0x2A,
    };
    thorbridge::ng::decrypt_block(k, t, block);
    CHECK(std::memcmp(block, expected, 16) == 0,
          "known-vector decrypt of zero input matches the Python reference");
}

void test_block_avalanche_full_block() {
    // Flip one bit in the ciphertext. RoundB (rounds 2..15) mixes
    // columns, so by round 17 the altered bit must have reached
    // every column. 32 flipped bits of 128 is a conservative
    // threshold (random tables give ~50% / 64 bits on average).
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k, t);
    std::uint8_t a[16] = {0}, b[16] = {0};
    b[7] ^= 0x01;
    thorbridge::ng::decrypt_block(k, t, a);
    thorbridge::ng::decrypt_block(k, t, b);
    int diff = bit_diff(a, b, 16);
    CHECK(diff >= 32,
          "one ciphertext bit flip cascades to many plaintext bits "
          "across the full block");
    bool each_column_changed = true;
    for (int col = 0; col < 4; ++col) {
        if (std::memcmp(a + col * 4, b + col * 4, 4) == 0) {
            each_column_changed = false;
        }
    }
    CHECK(each_column_changed,
          "RoundB diffuses a single-bit flip into every output column");
}

void test_key_avalanche_full_block() {
    thorbridge::ng::Key k1{}, k2{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k1, t);
    std::memcpy(&k2, &k1, sizeof(k1));
    k2.rounds[0][0] ^= 0x1u;
    std::uint8_t a[16], b[16];
    for (int i = 0; i < 16; ++i) a[i] = b[i] = 0xcc;
    thorbridge::ng::decrypt_block(k1, t, a);
    thorbridge::ng::decrypt_block(k2, t, b);
    int diff = bit_diff(a, b, 16);
    CHECK(diff >= 32,
          "one key bit flip cascades to many plaintext bits across the full block");
}

void test_table_avalanche_full_block() {
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t1{}, t2{};
    fill_synthetic(k, t1);
    std::memcpy(&t2, &t1, sizeof(t1));
    // Alter every entry of round-0 position-5 so any input is
    // guaranteed to hit the mutated table at round 0.
    for (int v = 0; v < 256; ++v) t2.rounds[0][5][v] ^= 0x1u;
    std::uint8_t a[16], b[16];
    for (int i = 0; i < 16; ++i) a[i] = b[i] = static_cast<std::uint8_t>(i * 13 + 5);
    thorbridge::ng::decrypt_block(k, t1, a);
    thorbridge::ng::decrypt_block(k, t2, b);
    int diff = bit_diff(a, b, 16);
    CHECK(diff >= 32,
          "altering a round-0 table cascades through RoundB into every column");
}

void test_ecb_matches_single_block() {
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k, t);
    std::uint8_t buf[48], ref[48];
    for (int i = 0; i < 48; ++i) buf[i] = ref[i] = static_cast<std::uint8_t>(i * 5 + 1);
    thorbridge::ng::decrypt_ecb(k, t, buf, 48);
    thorbridge::ng::decrypt_block(k, t, ref +  0);
    thorbridge::ng::decrypt_block(k, t, ref + 16);
    thorbridge::ng::decrypt_block(k, t, ref + 32);
    CHECK(std::memcmp(buf, ref, 48) == 0,
          "decrypt_ecb matches repeated decrypt_block");
}

void test_ecb_preserves_partial_tail() {
    thorbridge::ng::Key k{};
    thorbridge::ng::Tables t{};
    fill_synthetic(k, t);
    std::uint8_t buf[20], tail_copy[4];
    for (int i = 0; i < 20; ++i) buf[i] = static_cast<std::uint8_t>(i);
    std::memcpy(tail_copy, buf + 16, 4);
    thorbridge::ng::decrypt_ecb(k, t, buf, 20);
    CHECK(std::memcmp(buf + 16, tail_copy, 4) == 0,
          "decrypt_ecb leaves the trailing partial block untouched");
}

void test_load_key_sizes_and_endianness() {
    std::vector<std::uint8_t> raw(thorbridge::ng::kKeyBytes);
    for (std::size_t i = 0; i < raw.size(); ++i) raw[i] = static_cast<std::uint8_t>(i & 0xff);
    thorbridge::ng::Key k{};
    CHECK(thorbridge::ng::load_key(raw.data(), raw.size(), k),
          "load_key accepts exactly 272 bytes");
    // The second subkey's first uint32 is bytes [16, 17, 18, 19] of
    // the raw input, which are 0x10, 0x11, 0x12, 0x13 under our
    // formula. Little-endian decode gives 0x13121110.
    CHECK(k.rounds[1][0] == 0x13121110u,
          "load_key reads uint32 subkey words little-endian");
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

    std::size_t off = (((3 * thorbridge::ng::kTablesPerRound) + 5) *
                       thorbridge::ng::kEntriesPerTable + 128) *
                      thorbridge::ng::kBytesPerEntry;
    std::uint32_t want =
         static_cast<std::uint32_t>(raw[off + 0])        |
        (static_cast<std::uint32_t>(raw[off + 1]) <<  8) |
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

void test_pick_file_key_derivation() {
    // 101 synthetic 272-byte keys. Set each key's first byte to its
    // index so a wrong offset is instantly visible.
    constexpr std::size_t kPoolBytes =
        thorbridge::ng::kNumKeys * thorbridge::ng::kKeyBytes;
    std::vector<std::uint8_t> pool(kPoolBytes, 0);
    for (std::size_t i = 0; i < thorbridge::ng::kNumKeys; ++i) {
        pool[i * thorbridge::ng::kKeyBytes] = static_cast<std::uint8_t>(i);
    }

    struct Vec { std::uint32_t name_hash; std::uint32_t size; std::uint32_t expect_idx; };
    const Vec vecs[] = {
        {0u,          0u,     61u},    // 0 + 0 + 61 = 61
        {40u,         0u,      0u},    // 40 + 0 + 61 = 101 -> 0
        {0u,          40u,     0u},    // 0 + 40 + 61 = 101 -> 0
        {100u,        0u,     60u},    // 100 + 0 + 61 = 161 % 101 = 60
        {0xFFFFFFFFu, 0u,     60u},    // uint32 wrap: (-1 + 61) = 60, 60 % 101 = 60
        {0x46DDC648u, 0u,     20u},    // 'script.rpf' @ size 0
        {0x46DDC648u, 1024u,  34u},    // 'script.rpf' @ 1 KB
        {0x46DDC648u, 4096u,  76u},    // 'script.rpf' @ 4 KB
    };
    for (const auto& v : vecs) {
        const std::uint8_t* p =
            thorbridge::ng::pick_file_key(v.name_hash, v.size, pool.data());
        if (!p) {
            CHECK(false, "pick_file_key returned nullptr with a valid pool");
            continue;
        }
        std::size_t off = static_cast<std::size_t>(p - pool.data());
        std::size_t idx = off / thorbridge::ng::kKeyBytes;
        bool aligned = (off % thorbridge::ng::kKeyBytes) == 0;
        CHECK(aligned, "pick_file_key returns a pointer aligned to kKeyBytes");
        CHECK(idx == v.expect_idx,
              "pick_file_key chooses (hash + size + 61) % 101");
        CHECK(p[0] == static_cast<std::uint8_t>(v.expect_idx),
              "pick_file_key points at the right 272-byte slot");
    }
    CHECK(thorbridge::ng::pick_file_key(0u, 0u, nullptr) == nullptr,
          "pick_file_key refuses a null pool");
}

}  // namespace

int main() {
    test_determinism();
    test_zero_everything_yields_zero();
    test_zero_tables_yield_packed_last_key();
    test_known_vector_sequential_input();
    test_known_vector_zero_input();
    test_block_avalanche_full_block();
    test_key_avalanche_full_block();
    test_table_avalanche_full_block();
    test_ecb_matches_single_block();
    test_ecb_preserves_partial_tail();
    test_load_key_sizes_and_endianness();
    test_load_tables_sizes_and_endianness();
    test_pick_file_key_derivation();
    if (failures) {
        std::fprintf(stderr, "\n%d NG check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all NG checks passed.\n");
    return 0;
}
