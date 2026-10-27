#include "ng.h"

#include <cstring>

namespace thorbridge::ng {

namespace {

// Which 4 input positions feed each of the 4 output columns.
// RoundA (rounds 0, 1, 16) is block-diagonal; RoundB (rounds 2..15)
// is permuted so an input byte in one column reaches a different
// column, giving the cipher its cross-column diffusion.
constexpr std::size_t kRoundAPositions[4][4] = {
    { 0,  1,  2,  3},
    { 4,  5,  6,  7},
    { 8,  9, 10, 11},
    {12, 13, 14, 15},
};

constexpr std::size_t kRoundBPositions[4][4] = {
    {0,  7, 10, 13},
    {1,  4, 11, 14},
    {2,  5,  8, 15},
    {3,  6,  9, 12},
};

// Both round flavours have the same shape: for each output column c,
// XOR four position-table lookups together with the round subkey's
// uint32 at index c, then pack LE into the output 16 bytes.
void round(std::uint8_t block[kBlockBytes],
           const std::uint32_t subkey[kRoundKeyUInts],
           const std::uint32_t table[kTablesPerRound][kEntriesPerTable],
           const std::size_t positions[4][4]) {
    std::uint32_t out[4];
    for (int c = 0; c < 4; ++c) {
        out[c] =
            table[positions[c][0]][block[positions[c][0]]] ^
            table[positions[c][1]][block[positions[c][1]]] ^
            table[positions[c][2]][block[positions[c][2]]] ^
            table[positions[c][3]][block[positions[c][3]]] ^
            subkey[c];
    }
    for (int c = 0; c < 4; ++c) {
        block[c * 4 + 0] = static_cast<std::uint8_t>( out[c]        & 0xffu);
        block[c * 4 + 1] = static_cast<std::uint8_t>((out[c] >>  8) & 0xffu);
        block[c * 4 + 2] = static_cast<std::uint8_t>((out[c] >> 16) & 0xffu);
        block[c * 4 + 3] = static_cast<std::uint8_t>((out[c] >> 24) & 0xffu);
    }
}

}  // namespace

bool load_key(const std::uint8_t* src, std::size_t src_bytes, Key& out) {
    if (!src || src_bytes != kKeyBytes) return false;
    for (std::size_t r = 0; r < kRoundsPerBlock; ++r) {
        for (std::size_t u = 0; u < kRoundKeyUInts; ++u) {
            std::size_t off = (r * kRoundKeyUInts + u) * sizeof(std::uint32_t);
            out.rounds[r][u] =
                 static_cast<std::uint32_t>(src[off + 0])        |
                (static_cast<std::uint32_t>(src[off + 1]) <<  8) |
                (static_cast<std::uint32_t>(src[off + 2]) << 16) |
                (static_cast<std::uint32_t>(src[off + 3]) << 24);
        }
    }
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
                     static_cast<std::uint32_t>(src[off + 0])        |
                    (static_cast<std::uint32_t>(src[off + 1]) <<  8) |
                    (static_cast<std::uint32_t>(src[off + 2]) << 16) |
                    (static_cast<std::uint32_t>(src[off + 3]) << 24);
            }
        }
    }
    return true;
}

void decrypt_block(const Key& key, const Tables& tables,
                   std::uint8_t block[kBlockBytes]) {
    round(block, key.rounds[0],  tables.rounds[0],  kRoundAPositions);
    round(block, key.rounds[1],  tables.rounds[1],  kRoundAPositions);
    for (std::size_t r = 2; r <= 15; ++r) {
        round(block, key.rounds[r], tables.rounds[r], kRoundBPositions);
    }
    round(block, key.rounds[16], tables.rounds[16], kRoundAPositions);
}

void decrypt_ecb(const Key& key, const Tables& tables,
                 std::uint8_t* data, std::size_t bytes) {
    if (!data) return;
    std::size_t full = bytes & ~(kBlockBytes - 1);
    for (std::size_t off = 0; off < full; off += kBlockBytes) {
        decrypt_block(key, tables, data + off);
    }
}

const std::uint8_t* pick_file_key(std::uint32_t name_hash,
                                  std::uint32_t file_size,
                                  const std::uint8_t* pool_keys) {
    if (!pool_keys) return nullptr;
    std::uint32_t idx = (name_hash + file_size + 61u) % 101u;
    return pool_keys + idx * kKeyBytes;
}

}  // namespace thorbridge::ng
