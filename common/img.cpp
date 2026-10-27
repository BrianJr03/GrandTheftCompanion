#include "img.h"

#include <cstdio>
#include <cstring>

namespace thorbridge::img {

namespace {

constexpr std::size_t kSector = 2048;
constexpr std::size_t kEntrySize = 32;
constexpr std::size_t kNameLen = 24;

std::uint32_t rd_u32le(const std::uint8_t* p) {
    return static_cast<std::uint32_t>(p[0]) |
           (static_cast<std::uint32_t>(p[1]) << 8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

std::uint16_t rd_u16le(const std::uint8_t* p) {
    return static_cast<std::uint16_t>(p[0] | (p[1] << 8));
}

std::string hex_head(const std::uint8_t* p, std::size_t n) {
    char buf[128];
    std::size_t take = (n < 32) ? n : 32;
    std::size_t w = 0;
    for (std::size_t i = 0; i < take && w + 3 < sizeof(buf); ++i) {
        std::snprintf(buf + w, sizeof(buf) - w, "%02X ", p[i]);
        w += 3;
    }
    if (w > 0 && buf[w - 1] == ' ') buf[w - 1] = '\0';
    return std::string(buf);
}

}  // namespace

ParseResult parse_toc(const std::uint8_t* bytes, std::size_t n) {
    ParseResult r;
    if (!bytes || n < 8) {
        r.error = "archive is empty or truncated";
        return r;
    }
    r.head_hex = hex_head(bytes, n);

    if (!(bytes[0] == 'V' && bytes[1] == 'E' &&
          bytes[2] == 'R' && bytes[3] == '2')) {
        r.error = "unsupported IMG format: expected 'VER2' magic";
        return r;
    }

    std::uint32_t count = rd_u32le(bytes + 4);
    // Sanity: count * 32 + 8 <= n (TOC must fit).
    if (static_cast<std::uint64_t>(count) * kEntrySize + 8ull >
        static_cast<std::uint64_t>(n)) {
        r.error = "VER2 TOC extends past end of archive";
        return r;
    }
    r.entries.reserve(count);
    for (std::uint32_t i = 0; i < count; ++i) {
        const std::uint8_t* e = bytes + 8 + i * kEntrySize;
        Entry en;
        std::uint32_t off_sect  = rd_u32le(e + 0);
        std::uint16_t streaming = rd_u16le(e + 4);
        std::uint16_t full_sect = rd_u16le(e + 6);
        std::uint16_t size_sect = (streaming != 0 ? streaming : full_sect);
        // name field is 24 bytes, NUL-padded. Preserve content up to the
        // first NUL — the stock exporter writes mixed case so we keep it.
        const char* name = reinterpret_cast<const char*>(e + 8);
        std::size_t name_len = 0;
        while (name_len < kNameLen && name[name_len] != '\0') ++name_len;
        en.name.assign(name, name_len);
        en.offset = static_cast<std::uint64_t>(off_sect) * kSector;
        en.size = static_cast<std::uint32_t>(size_sect) * kSector;
        // Boundary check against the archive extent.
        if (en.offset + en.size > n) {
            char msg[160];
            std::snprintf(msg, sizeof(msg),
                "VER2 entry '%s' (#%u) extends past archive (%llu + %u > %zu)",
                en.name.c_str(), static_cast<unsigned>(i),
                static_cast<unsigned long long>(en.offset),
                static_cast<unsigned>(en.size), n);
            r.error = msg;
            return r;
        }
        r.entries.push_back(std::move(en));
    }

    r.ok = true;
    return r;
}

}  // namespace thorbridge::img
