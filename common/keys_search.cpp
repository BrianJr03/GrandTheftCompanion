#include "keys_search.h"

#include <climits>
#include <cstring>

namespace thorbridge::keys {

namespace {

int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
    if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
    return -1;
}

}  // namespace

std::size_t find_blob(const std::uint8_t* exe, std::size_t exe_bytes,
                      std::size_t blob_bytes,
                      const std::uint8_t digest[sha1::kDigestBytes]) {
    if (!exe || blob_bytes == 0 || blob_bytes > exe_bytes) return SIZE_MAX;
    const std::size_t last = exe_bytes - blob_bytes;
    std::uint8_t have[sha1::kDigestBytes];
    for (std::size_t off = 0; off <= last; off += kAlignment) {
        sha1::hash(exe + off, blob_bytes, have);
        if (sha1::equals(have, digest)) return off;
    }
    return SIZE_MAX;
}

bool parse_hex(const char* hex, std::uint8_t out[sha1::kDigestBytes]) {
    if (!hex) return false;
    for (std::size_t i = 0; i < sha1::kDigestBytes; ++i) {
        int hi = hex_val(hex[i * 2]);
        int lo = hex_val(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<std::uint8_t>((hi << 4) | lo);
    }
    return hex[sha1::kDigestBytes * 2] == '\0';
}

}  // namespace thorbridge::keys
