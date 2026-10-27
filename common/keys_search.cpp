#include "keys_search.h"

#include <algorithm>
#include <climits>
#include <cstring>
#include <vector>

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

std::size_t find_blobs(const std::uint8_t* exe, std::size_t exe_bytes,
                       std::size_t blob_bytes,
                       const std::uint8_t* targets,
                       std::size_t targets_count,
                       std::size_t* out_offsets) {
    if (!exe || !targets || !out_offsets ||
        blob_bytes == 0 || targets_count == 0 || blob_bytes > exe_bytes) {
        if (out_offsets) {
            for (std::size_t i = 0; i < targets_count; ++i) out_offsets[i] = SIZE_MAX;
        }
        return 0;
    }
    // Build an index of (digest, original slot) and sort by digest so
    // the hot loop can binary-search instead of linear-scan all targets.
    struct Entry {
        const std::uint8_t* digest;
        std::size_t slot;
    };
    std::vector<Entry> idx;
    idx.reserve(targets_count);
    for (std::size_t i = 0; i < targets_count; ++i) {
        idx.push_back({targets + i * sha1::kDigestBytes, i});
        out_offsets[i] = SIZE_MAX;
    }
    std::sort(idx.begin(), idx.end(), [](const Entry& a, const Entry& b) {
        return std::memcmp(a.digest, b.digest, sha1::kDigestBytes) < 0;
    });

    const std::size_t last = exe_bytes - blob_bytes;
    std::uint8_t have[sha1::kDigestBytes];
    std::size_t matched = 0;
    std::size_t remaining = targets_count;
    for (std::size_t off = 0; off <= last && remaining > 0; off += kAlignment) {
        sha1::hash(exe + off, blob_bytes, have);
        // Binary search for `have` inside the sorted index.
        auto lo = idx.begin();
        auto hi = idx.end();
        while (lo < hi) {
            auto mid = lo + (hi - lo) / 2;
            int cmp = std::memcmp(have, mid->digest, sha1::kDigestBytes);
            if (cmp == 0) {
                if (out_offsets[mid->slot] == SIZE_MAX) {
                    out_offsets[mid->slot] = off;
                    ++matched;
                    --remaining;
                }
                break;
            }
            if (cmp < 0) hi = mid; else lo = mid + 1;
        }
    }
    return matched;
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
