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

// Batched blob search. Walks `exe` ONCE at DWORD alignment, SHA-1'ing
// each `blob_bytes`-sized slice and looking the digest up in the
// sorted `targets` set. For every target that matches, writes the
// slice offset into the matching slot of `out_offsets`; unmatched
// slots are set to SIZE_MAX. Returns the number of distinct targets
// that matched.
//
// Rationale: GTA V's keys_v module scans for 375 blobs across a
// ~80 MB retail exe. Calling find_blob() 375 times costs 375 full
// passes through the exe; find_blobs batches by blob size so the
// exe is walked 4 times total (one pass per distinct size). The
// targets array is laid out as `targets_count` × 20 bytes contiguously;
// out_offsets must hold `targets_count` slots. The target digests
// are consulted by sorted-list binary search inside the hot loop, so
// adding targets costs log(N) per candidate offset, not N.
std::size_t find_blobs(const std::uint8_t* exe, std::size_t exe_bytes,
                       std::size_t blob_bytes,
                       const std::uint8_t* targets,
                       std::size_t targets_count,
                       std::size_t* out_offsets);

// Parse a hex SHA-1 digest in ASCII ("40 hex chars") into the 20-byte
// binary form. Returns false if the input is not exactly 40 hex chars.
bool parse_hex(const char* hex, std::uint8_t out[sha1::kDigestBytes]);

}  // namespace thorbridge::keys
