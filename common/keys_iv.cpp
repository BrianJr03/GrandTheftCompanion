#include "keys_iv.h"

#include <cstring>

#include "keys_search.h"
#include "sha1.h"

namespace thorbridge::keys_iv {

const char kAesKeySha1Hex[41] =
    "DEA375EF1E6EF2223A1221C2C575C47BF17EFA5E";

bool locate_aes_key(const std::uint8_t* exe, std::size_t exe_bytes,
                    std::uint8_t out_key[kAesKeyBytes]) {
    std::uint8_t want[sha1::kDigestBytes];
    if (!keys::parse_hex(kAesKeySha1Hex, want)) return false;
    std::size_t off = keys::find_blob(exe, exe_bytes, kAesKeyBytes, want);
    if (off == SIZE_MAX) return false;
    std::memcpy(out_key, exe + off, kAesKeyBytes);
    return true;
}

}  // namespace thorbridge::keys_iv
