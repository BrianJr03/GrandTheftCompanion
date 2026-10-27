#pragma once

// Rockstar's custom LUT-driven filename hash used by GTA V's RPF7
// archive layer. Despite the handoff doc calling this "Jenkins hash"
// (and this module keeping the same shorthand name for continuity),
// it is NOT Bob Jenkins' one-at-a-time hash. The LUT is a 256-byte
// table living inside the retail GTA5.exe that we locate at install
// time via common/keys_v.cpp (kLutSha1Hex).
//
// Algorithm, per Neodymium's GTAKeys.cs::GTA5Hash in CodeWalker (MIT):
//
//     result = 0
//     for each byte c in filename:
//         temp = 1025 * (LUT[c] + result)             (uint32 math)
//         result = (temp >> 6) XOR temp
//     return 32769 * (((9 * result) >> 11) XOR (9 * result))
//
// The one consumer here is ng::pick_file_key, which derives the
// index into the 101-key NG pool from (hash(name) + size + 61) % 101.

#include <cstddef>
#include <cstdint>

namespace thorbridge::jenk {

constexpr std::size_t kLutBytes = 256;

// Hash a byte sequence of known length. Archive layer filenames are
// bytes, not C strings, so this takes an explicit length to tolerate
// embedded nulls (common in Rockstar's own tooling).
std::uint32_t hash(const std::uint8_t* text, std::size_t len,
                   const std::uint8_t lut[kLutBytes]);

// Convenience overload for null-terminated C strings.
std::uint32_t hash(const char* text, const std::uint8_t lut[kLutBytes]);

}  // namespace thorbridge::jenk
