#pragma once

// NG block cipher, decrypt-only. Rockstar's PC build of GTA V uses this
// cipher (not plain AES) for most RPF7 entry headers; the RPF7 header's
// per-entry encryption flag picks between NG and AES-256 ECB, so we
// still need both.
//
// Spec at a glance (reconstructed from public reverse-engineering of
// retail builds plus the handoff doc for this task):
//
//   * The cipher operates on 16-byte blocks under a 272-byte key.
//     The 272-byte key is laid out as 17 round-subkeys of 16 bytes
//     each. Rockstar ships 101 such 272-byte keys inside the retail
//     exe; the per-file selection of 17 subkeys (one per round) is a
//     caller concern (rpf7 / keys_v).
//   * Decryption is table-driven. 272 lookup tables total: 17 rounds
//     by 16 input byte positions, each a 256-entry map from one
//     ciphertext byte to one 32-bit mask. Positions 0..3 contribute
//     their masks into output bytes 0..3; positions 4..7 into bytes
//     4..7; positions 8..11 into 8..11; positions 12..15 into 12..15.
//     That is, the transform is BLOCK-DIAGONAL per 32-bit column:
//     each column decrypts independently. All diffusion is within a
//     column, across 17 rounds. (The property makes the cipher very
//     weak against known-plaintext per column, which matches the
//     public history of the format being broken within days of
//     release. We're reading the format, not defending it.)
//   * Rounds 0, 1, and 16 are "A": XOR the round subkey into the
//     block first, then do the table pass. Rounds 2..15 are "B":
//     table pass first, then XOR the subkey. Shape matches the
//     InvAddRoundKey + InvMix + InvShiftRows + InvSub pattern folded
//     into tables plus a final whitening on the last round.
//
// We ship decrypt only (we never author encrypted archives) and we
// never hold key material except on the user's own machine from their
// own exe. See common/keys_search.h for the hash-driven location
// scheme that keeps actual bytes out of this repo.

#include <cstddef>
#include <cstdint>

namespace thorbridge::ng {

constexpr std::size_t kBlockBytes      = 16;
constexpr std::size_t kRoundsPerBlock  = 17;
constexpr std::size_t kRoundKeyBytes   = 16;
constexpr std::size_t kKeyBytes        = kRoundKeyBytes * kRoundsPerBlock;  // 272

// 101 of these 272-byte keys live inside the retail GTA5.exe. Shipping
// hashes only, we locate each one at install time.
constexpr std::size_t kNumKeys         = 101;

constexpr std::size_t kTablesPerRound  = 16;
constexpr std::size_t kEntriesPerTable = 256;
constexpr std::size_t kBytesPerEntry   = 4;
constexpr std::size_t kBytesPerRound   =
    kTablesPerRound * kEntriesPerTable * kBytesPerEntry;              // 16,384
constexpr std::size_t kTablesBytes     =
    kBytesPerRound * kRoundsPerBlock;                                 // 278,528

// A loaded 272-byte key, split as 17 round-subkeys. load_key below
// parses raw bytes into this shape; the caller is responsible for
// having first stitched together the right 17 subkeys from the retail
// pool using the per-file index derivation.
struct Key {
    std::uint8_t rounds[kRoundsPerBlock][kRoundKeyBytes];
};

// All 272 decrypt-tables, grouped per round. load_tables parses the
// retail pool of 278,528 little-endian bytes into this shape.
struct Tables {
    std::uint32_t rounds[kRoundsPerBlock][kTablesPerRound][kEntriesPerTable];
};

// Reinterpret raw bytes as a Key. Returns false if `src_bytes` is not
// exactly kKeyBytes (272).
bool load_key(const std::uint8_t* src, std::size_t src_bytes, Key& out);

// Reinterpret raw bytes as the decrypt-table pool. The retail layout
// is little-endian uint32, as published by the public tooling. Returns
// false if `src_bytes` is not exactly kTablesBytes (278,528).
bool load_tables(const std::uint8_t* src, std::size_t src_bytes, Tables& out);

// Decrypt one 16-byte block in place.
void decrypt_block(const Key& key, const Tables& tables,
                   std::uint8_t block[kBlockBytes]);

// Decrypt a whole buffer in place, ECB-style. Only whole 16-byte
// blocks are processed; any trailing partial block is left untouched.
// The caller sets `bytes`; passing a zero or non-multiple is safe.
void decrypt_ecb(const Key& key, const Tables& tables,
                 std::uint8_t* data, std::size_t bytes);

}  // namespace thorbridge::ng
