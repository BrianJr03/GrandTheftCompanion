#pragma once

// Locate GTA V key material inside the user's own GTA5.exe. Mirrors
// common/keys_iv.h but with GTA V's four blob families:
//
//   * 1   AES-256 key              (32 bytes). Older RPF7 entries.
//   * 101 NG 272-byte keys. The 17-round block cipher's key pool;
//         rpf7 / keys_v picks 17 of the 101 per file via a filename
//         + size hash to form the per-file 17-round key.
//   * 272 NG 1024-byte decrypt tables. One per (round, position);
//         17 rounds × 16 positions = 272 position tables of 256 ×
//         uint32 = 1024 bytes each. See common/ng.cpp.
//   * 1   LUT (256 bytes). Lookup table used during NG decryption.
//
// Shipping SHA-1 digests only. The retail key / table bytes stay
// inside each user's own GTA5.exe. The published digest list comes
// from Neodymium's GTAKeys.cs in the CodeWalker project (MIT); see
// asi-v/NOTICE for attribution.

#include <cstddef>
#include <cstdint>

namespace thorbridge::keys_v {

constexpr std::size_t kAesKeyBytes   = 32;
constexpr std::size_t kNgKeyBytes    = 272;
constexpr std::size_t kNgTableBytes  = 1024;    // 256 entries × uint32
constexpr std::size_t kLutBytes      = 256;

constexpr std::size_t kNgKeyCount    = 101;
constexpr std::size_t kNgTableCount  = 272;

// Published SHA-1 fingerprints (hex, uppercase, 40 chars + null).
extern const char kAesKeySha1Hex[41];
extern const char kLutSha1Hex[41];
extern const char* const kNgKeySha1Hex[kNgKeyCount];
extern const char* const kNgTableSha1Hex[kNgTableCount];

// All key / table bytes extracted from a user-supplied GTA5.exe.
// Sized at ~306 KB, so allocate on the heap. The flat ng_keys and
// ng_decrypt_tables buffers are indexed as
//   ng_keys[i * kNgKeyBytes .. (i+1) * kNgKeyBytes]
//   ng_decrypt_tables[i * kNgTableBytes .. (i+1) * kNgTableBytes]
struct ExtractedKeys {
    std::uint8_t aes_key           [kAesKeyBytes];                              //      32
    std::uint8_t ng_keys           [kNgKeyCount   * kNgKeyBytes];               //  27,472
    std::uint8_t ng_decrypt_tables [kNgTableCount * kNgTableBytes];             // 278,528
    std::uint8_t lut               [kLutBytes];                                 //     256
};

constexpr std::size_t kExtractedKeysBytes = sizeof(ExtractedKeys);

// Scan `exe` for every published blob and copy the matched bytes into
// `out`. Returns true only if all 375 blobs are located. Four passes
// over the exe (one per distinct blob size) rather than 375.
bool locate_all(const std::uint8_t* exe, std::size_t exe_bytes,
                ExtractedKeys& out);

}  // namespace thorbridge::keys_v
