#pragma once

// Hash-driven blob lookup inside a game executable. Given a candidate
// buffer, a target SHA-1 digest, and the blob size, this returns the
// offset of the matching slice (if any). The whitelist of hashes
// lives in keys_iv.cpp / keys_v.cpp; shipping only the hashes keeps
// the actual key material in each user's own exe.
//
// The scan is: for every DWORD-aligned candidate offset where a full
// `blob_bytes` slice would fit, SHA-1 that slice and compare. This is
// slower than CodeWalker's parallel 1 MB-block pipeline but entirely
// adequate for the atlas worker (one scan per archive extraction,
// results cached next to the exe for subsequent runs).

#include <cstddef>
#include <cstdint>

#include "sha1.h"

namespace thorbridge::keys {

constexpr std::size_t kAlignment = 4;  // DWORD-aligned, matches CodeWalker

// Return the offset of the first slice whose SHA-1 equals `digest`,
// or SIZE_MAX if no match. `exe` is a buffer backed by the user's exe
// file; this function reads but does not modify it.
std::size_t find_blob(const std::uint8_t* exe, std::size_t exe_bytes,
                      std::size_t blob_bytes,
                      const std::uint8_t digest[sha1::kDigestBytes]);

// Parse a hex SHA-1 digest in ASCII ("40 hex chars") into the 20-byte
// binary form. Returns false if the input is not exactly 40 hex chars.
bool parse_hex(const char* hex, std::uint8_t out[sha1::kDigestBytes]);

}  // namespace thorbridge::keys
