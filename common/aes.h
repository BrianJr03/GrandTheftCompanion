#pragma once

// FIPS-197 AES-256 implementation, decrypt-only. We only read Rockstar
// archives, never write encrypted ones, so there is no need for the
// encryption path and shipping only decryption makes the "no crypto
// toolkit" story cleaner. The implementation is clean-room: tables and
// operations follow the NIST specification directly, no third-party
// source was consulted.
//
// Call site cost: one key_schedule per archive (240 bytes of expanded
// round keys), then decrypt_block per 16-byte TOC block.

#include <cstddef>
#include <cstdint>

namespace thorbridge::aes {

constexpr std::size_t kBlockBytes = 16;   // AES fixed
constexpr std::size_t kKeyBytes   = 32;   // AES-256
constexpr std::size_t kRounds     = 14;   // AES-256
constexpr std::size_t kExpandedBytes = kBlockBytes * (kRounds + 1);  // 240

// 240 bytes of round keys derived from a 32-byte AES-256 key.
struct Schedule {
    std::uint8_t rk[kExpandedBytes];
};

// Key expansion per FIPS-197 §5.2. `key` must point at 32 bytes.
void key_schedule(const std::uint8_t key[kKeyBytes], Schedule& out);

// Decrypt one 16-byte block in place. Equivalent to EqInvCipher per
// FIPS-197 §5.3.5 but written as the straightforward reverse of the
// Cipher pseudocode so there is no need for the "equivalent" tables.
void decrypt_block(const Schedule& s, std::uint8_t block[kBlockBytes]);

// Convenience: decrypt a whole buffer in-place, AES-256-ECB, `rounds`
// times. GTA IV's RPF3 / IMG v3 encrypted regions get `rounds=16`.
// `bytes` must be a positive multiple of kBlockBytes; any trailing
// partial block is left untouched.
void decrypt_ecb(const Schedule& s, std::uint8_t* data, std::size_t bytes,
                 int rounds);

}  // namespace thorbridge::aes
