#pragma once

// Locate the GTA IV AES-256 key inside the user's own GTAIV.exe. The
// mechanism is identical to the one CodeWalker / rage-cli use for GTA
// V — SHA-1 hash of a candidate 32-byte window must match a public
// digest. The digest is a published fingerprint of the key material,
// not the key itself.

#include <cstddef>
#include <cstdint>

namespace thorbridge::keys_iv {

constexpr std::size_t kAesKeyBytes = 32;

// Published SHA-1 of the GTA IV AES-256 key. See the Python helper in
// github.com/ReneSchimmelpfennig/GTA-IV-PS3Decode (tools/rpf3.py) and
// the key-detection in several other open-source IV tools. Shipping
// the hash lets us LOCATE the key inside the user's exe; the key
// bytes themselves never leave that exe.
extern const char kAesKeySha1Hex[41];

// Scan `exe` for the first 32-byte slice whose SHA-1 matches the IV
// AES key fingerprint, copy it into `out_key`, and return true. The
// scan is DWORD-aligned, as the key appears aligned in every retail
// build we've seen. Returns false if no match (wrong exe build or
// re-signed binary).
bool locate_aes_key(const std::uint8_t* exe, std::size_t exe_bytes,
                    std::uint8_t out_key[kAesKeyBytes]);

}  // namespace thorbridge::keys_iv
