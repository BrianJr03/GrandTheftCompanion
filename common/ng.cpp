#include "ng.h"

#include <cstring>

namespace thorbridge::ng {

namespace {

// One table pass. For each of the 16 input byte positions, index the
// per-position table by the byte value to get a 32-bit mask, then XOR
// that mask into the 4-byte output column for this input's quartile
// (positions 0..3 feed column 0, 4..7 feed column 1, etc). The retail
// tables publish their masks little-endian; the Tables struct we take
// here is already in native uint32 (see load_tables).
void table_pass(
    std::uint8_t block[kBlockBytes],
    const std::uint32_t table[kTablesPerRound][kEntriesPerTable]) {
    std::uint8_t res[kBlockBytes] = {};
    for (std::size_t i = 0; i < kBlockBytes; ++i) {
        std::uint32_t val = table[i][block[i]];
        std::size_t base = (i / 4) * 4;
        res[base + 0] = static_cast<std::uint8_t>(res[base + 0] ^ (val & 0xffu));
        res[base + 1] = static_cast<std::uint8_t>(res[base + 1] ^ ((val >> 8) & 0xffu));
        res[base + 2] = static_cast<std::uint8_t>(res[base + 2] ^ ((val >> 16) & 0xffu));
        res[base + 3] = static_cast<std::uint8_t>(res[base + 3] ^ ((val >> 24) & 0xffu));
    }
    std::memcpy(block, res, kBlockBytes);
}

void xor_bytes(std::uint8_t block[kBlockBytes],
               const std::uint8_t key[kRoundKeyBytes]) {
    for (std::size_t i = 0; i < kBlockBytes; ++i) block[i] ^= key[i];
}

// RoundA: XOR the round subkey first, then do the table pass. Applied
// at rounds 0, 1, 16.
void round_a(std::uint8_t block[kBlockBytes],
             const std::uint8_t key[kRoundKeyBytes],
             const std::uint32_t table[kTablesPerRound][kEntriesPerTable]) {
    xor_bytes(block, key);
    table_pass(block, table);
}

// RoundB: table pass first, then XOR. Applied at rounds 2..15.
void round_b(std::uint8_t block[kBlockBytes],
             const std::uint8_t key[kRoundKeyBytes],
             const std::uint32_t table[kTablesPerRound][kEntriesPerTable]) {
    table_pass(block, table);
    xor_bytes(block, key);
}

}  // namespace

bool load_key(const std::uint8_t* src, std::size_t src_bytes, Key& out) {
    if (!src || src_bytes != kKeyBytes) return false;
    std::memcpy(out.rounds, src, kKeyBytes);
    return true;
}

bool load_tables(const std::uint8_t* src, std::size_t src_bytes, Tables& out) {
    if (!src || src_bytes != kTablesBytes) return false;
    for (std::size_t r = 0; r < kRoundsPerBlock; ++r) {
        for (std::size_t p = 0; p < kTablesPerRound; ++p) {
            for (std::size_t e = 0; e < kEntriesPerTable; ++e) {
                std::size_t off =
                    (((r * kTablesPerRound) + p) * kEntriesPerTable + e) *
                    kBytesPerEntry;
                out.rounds[r][p][e] =
                    (static_cast<std::uint32_t>(src[off + 0])) |
                    (static_cast<std::uint32_t>(src[off + 1]) << 8) |
                    (static_cast<std::uint32_t>(src[off + 2]) << 16) |
                    (static_cast<std::uint32_t>(src[off + 3]) << 24);
            }
        }
    }
    return true;
}

void decrypt_block(const Key& key, const Tables& tables,
                   std::uint8_t block[kBlockBytes]) {
    round_a(block, key.rounds[0],  tables.rounds[0]);
    round_a(block, key.rounds[1],  tables.rounds[1]);
    for (std::size_t r = 2; r <= 15; ++r) {
        round_b(block, key.rounds[r], tables.rounds[r]);
    }
    round_a(block, key.rounds[16], tables.rounds[16]);
}

void decrypt_ecb(const Key& key, const Tables& tables,
                 std::uint8_t* data, std::size_t bytes) {
    if (!data) return;
    std::size_t full = bytes & ~(kBlockBytes - 1);
    for (std::size_t off = 0; off < full; off += kBlockBytes) {
        decrypt_block(key, tables, data + off);
    }
}

}  // namespace thorbridge::ng
