#pragma once

// RFC 3174 SHA-1 — clean-room implementation of the hash algorithm the
// hash-search loop uses to locate the Rockstar AES / NG blobs inside
// the user's game exe. We never ship or compare key bytes; the public
// hashes committed to this repo (see common/keys_iv.cpp, keys_v.cpp)
// are the only side-channel.
//
// We don't need SHA-1 for integrity in the modern sense — it's used
// here as a content-addressable fingerprint over fixed-size blobs.

#include <cstddef>
#include <cstdint>

namespace thorbridge::sha1 {

constexpr std::size_t kDigestBytes = 20;

// Single-shot SHA-1 over `data`; writes 20 bytes to `digest`.
void hash(const std::uint8_t* data, std::size_t bytes,
          std::uint8_t digest[kDigestBytes]);

// Equality check that is NOT constant-time. We're comparing a hash to
// a hard-coded SHA-1 whitelist; timing side channels have nothing to
// learn (the right answer is written in the source).
bool equals(const std::uint8_t a[kDigestBytes], const std::uint8_t b[kDigestBytes]);

}  // namespace thorbridge::sha1
