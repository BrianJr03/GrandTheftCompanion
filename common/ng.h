#pragma once

// NG block cipher, decrypt-only. Rockstar's PC build of GTA V uses
// this cipher (not plain AES) for most RPF7 entry headers; the RPF7
// per-entry encryption flag picks between NG and AES-256 ECB, so we
// need both.
//
// Algorithm, per Neodymium's GTACrypto.cs in CodeWalker (MIT):
//
//   * 16-byte blocks, 272-byte key laid out as 17 round-subkeys of
//     4 × uint32 (16 bytes). Rockstar ships 101 such 272-byte keys
//     inside GTA5.exe; the retail per-file selection picks one of
//     the 101 via (name_hash + size + 61) % 101 and uses its whole
//     272-byte block (see pick_file_key below).
//   * Decryption is table-driven. 272 lookup tables total: 17 rounds
//     by 16 input byte positions, each a 256-entry map from one
//     ciphertext byte to one 32-bit mask. Each round reads 16 of
//     these masks and combines four of them into each output uint32
//     along with the round's 4-uint subkey:
//
//         x_col = table[p0][d[p0]] ^ table[p1][d[p1]]
//               ^ table[p2][d[p2]] ^ table[p3][d[p3]]
//               ^ subkey[col]
//
//     Rounds 0, 1, 16 ("RoundA") use the block-diagonal position
//     tuples
//         col 0 <- positions {0, 1, 2, 3}
//         col 1 <- positions {4, 5, 6, 7}
//         col 2 <- positions {8, 9, 10, 11}
//         col 3 <- positions {12, 13, 14, 15}
//     Rounds 2..15 ("RoundB") use a permuted set that mixes bytes
//     across columns (inverse-ShiftRows baked into the table pass):
//         col 0 <- positions {0, 7, 10, 13}
//         col 1 <- positions {1, 4, 11, 14}
//         col 2 <- positions {2, 5, 8, 15}
//         col 3 <- positions {3, 6, 9, 12}
//
// We ship decrypt only (we never author encrypted archives) and we
// never hold key material except on the user's own machine, from
// their own exe. See common/keys_search.h for the hash-driven
// location scheme that keeps bytes out of this repo.

#include <cstddef>
#include <cstdint>

namespace thorbridge::ng {

constexpr std::size_t kBlockBytes       = 16;
constexpr std::size_t kRoundsPerBlock   = 17;
constexpr std::size_t kRoundKeyUInts    = 4;                                     // 4 × uint32
constexpr std::size_t kRoundKeyBytes    = kRoundKeyUInts * sizeof(std::uint32_t);// 16
constexpr std::size_t kKeyBytes         = kRoundKeyBytes * kRoundsPerBlock;      // 272

// 101 keys ship inside the retail GTA5.exe. Shipping hashes only, we
// locate them at install time (see common/keys_v.h).
constexpr std::size_t kNumKeys          = 101;

constexpr std::size_t kTablesPerRound   = 16;
constexpr std::size_t kEntriesPerTable  = 256;
constexpr std::size_t kBytesPerEntry    = 4;
constexpr std::size_t kBytesPerRound    =
    kTablesPerRound * kEntriesPerTable * kBytesPerEntry;                         //  16,384
constexpr std::size_t kTablesBytes      =
    kBytesPerRound * kRoundsPerBlock;                                            // 278,528

// A loaded 272-byte key, split as 17 round-subkeys of 4 × uint32.
// load_key below parses raw little-endian bytes into this shape.
struct Key {
    std::uint32_t rounds[kRoundsPerBlock][kRoundKeyUInts];
};

// All 272 decrypt-tables, grouped per round. load_tables parses the
// retail pool of 278,528 little-endian bytes into this shape.
struct Tables {
    std::uint32_t rounds[kRoundsPerBlock][kTablesPerRound][kEntriesPerTable];
};

// Reinterpret raw bytes as a Key (little-endian uint32 reads).
// Returns false if `src_bytes` is not exactly kKeyBytes (272).
bool load_key(const std::uint8_t* src, std::size_t src_bytes, Key& out);

// Reinterpret raw bytes as the decrypt-table pool. Little-endian
// uint32 reads. Returns false if `src_bytes` is not exactly
// kTablesBytes (278,528).
bool load_tables(const std::uint8_t* src, std::size_t src_bytes, Tables& out);

// Decrypt one 16-byte block in place.
void decrypt_block(const Key& key, const Tables& tables,
                   std::uint8_t block[kBlockBytes]);

// Decrypt a whole buffer in place, ECB-style. Only whole 16-byte
// blocks are processed; any trailing partial block is left untouched.
void decrypt_ecb(const Key& key, const Tables& tables,
                 std::uint8_t* data, std::size_t bytes);

// Pick one of the 101 NG keys for a given file identity. The retail
// derivation (per GTACrypto.cs::GetNGKey) is:
//
//     keyidx = (name_hash + file_size + 61) % 101
//
// `name_hash` comes from Rockstar's custom LUT-driven filename hash
// in common/jenk.h. `pool_keys` is the 101-key pool loaded by
// keys_v::locate_all (laid out as 101 × 272 bytes contiguously).
// Returns a pointer into the pool, or nullptr on bad input.
const std::uint8_t* pick_file_key(std::uint32_t name_hash,
                                  std::uint32_t file_size,
                                  const std::uint8_t* pool_keys);

}  // namespace thorbridge::ng
