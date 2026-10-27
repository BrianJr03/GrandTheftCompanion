#include "img_v3.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace thorbridge::img_v3 {

namespace {

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

ParseResult parse_toc(const std::uint8_t* bytes, std::size_t bytes_size,
                      const std::uint8_t key[aes::kKeyBytes]) {
    ParseResult r;
    if (!bytes || bytes_size < 32) {
        r.error = "archive too small for an IMG v3 header";
        return r;
    }
    aes::Schedule sched{};
    aes::key_schedule(key, sched);

    // 1. Decrypt the first 16 bytes in a temporary buffer (don't mutate
    //    the caller's archive; it may be mmap-RO on device).
    std::uint8_t hdr[16];
    std::memcpy(hdr, bytes, 16);
    aes::decrypt_ecb(sched, hdr, 16, kAesRounds);

    std::uint32_t magic   = rd_u32le(hdr + 0);
    std::uint32_t version = rd_u32le(hdr + 4);
    std::uint32_t count   = rd_u32le(hdr + 8);
    std::uint32_t tbl_sz  = rd_u32le(hdr + 12);
    if (magic != kMagic || version != kVersion) {
        char msg[128];
        std::snprintf(msg, sizeof(msg),
                      "bad IMG v3 header after AES-256 x%zu: magic=0x%08X, "
                      "version=%u (expected 0x%08X / 3)",
                      kAesRounds, magic, version, kMagic);
        r.error = msg;
        return r;
    }

    std::uint16_t item_size = rd_u16le(bytes + 16);
    std::uint16_t unknown   = rd_u16le(bytes + 18);
    if (item_size != kEntryBytes) {
        char msg[96];
        std::snprintf(msg, sizeof(msg),
                      "IMG v3 item_size %u != expected %zu",
                      static_cast<unsigned>(item_size), kEntryBytes);
        r.error = msg;
        return r;
    }
    (void)unknown;  // 0x00E9 observed; not currently load-bearing.

    r.item_count = count;
    r.table_size = tbl_sz;
    r.head_hex_plain = hex_head(hdr, 16);

    // 2. Decrypt entries starting at offset 20. The entry block spans
    //    count * 16 bytes; the filename table follows and runs until
    //    the TOC's declared end (tbl_sz accounts for header + entries
    //    + filenames and padding, per the FusionFix spec reading).
    const std::size_t entries_offset = 20;
    const std::size_t entries_bytes = static_cast<std::size_t>(count) * kEntryBytes;
    if (entries_offset + entries_bytes > bytes_size) {
        r.error = "entry block extends past end of archive";
        return r;
    }
    std::vector<std::uint8_t> entries(entries_bytes);
    std::memcpy(entries.data(), bytes + entries_offset, entries_bytes);
    aes::decrypt_ecb(sched, entries.data(), entries_bytes, kAesRounds);

    // 3. Filename table: starts right after the entries, runs through
    //    the end of the TOC. We size it off tbl_sz, falling back to a
    //    sector-boundary scan if tbl_sz looks implausible.
    std::size_t name_offset = entries_offset + entries_bytes;
    std::size_t toc_end;
    if (tbl_sz >= name_offset && tbl_sz <= bytes_size) {
        toc_end = tbl_sz;
    } else {
        // Round up name_offset to the next sector; file bodies always
        // start at sector 1+ so this is a safe upper bound.
        toc_end = (name_offset + kSector - 1) & ~(kSector - 1);
        if (toc_end > bytes_size) toc_end = bytes_size;
    }
    if (toc_end < name_offset) {
        r.error = "table_size says the TOC ends before the filename table";
        return r;
    }
    std::size_t names_bytes = toc_end - name_offset;
    std::vector<std::uint8_t> names(names_bytes);
    if (names_bytes) {
        std::memcpy(names.data(), bytes + name_offset, names_bytes);
        // Only full 16-byte blocks are encrypted. Partial trailing
        // block is plaintext (fixes the "last name is garbage" bug
        // X-Seti documented).
        std::size_t full = names_bytes & ~std::size_t{15};
        if (full) aes::decrypt_ecb(sched, names.data(), full, kAesRounds);
    }

    // 4. Walk the name table into a (offset -> name) index. Each name
    //    is a NUL-terminated string; names are packed back-to-back.
    std::vector<std::string> name_list;
    name_list.reserve(count);
    std::size_t p = 0;
    while (p < names.size() && name_list.size() < count) {
        std::size_t start = p;
        while (p < names.size() && names[p] != 0) ++p;
        name_list.emplace_back(reinterpret_cast<const char*>(names.data() + start),
                               p - start);
        if (p < names.size()) ++p;  // skip NUL
    }

    // 5. Build the Entry list by stitching each entry row's fields
    //    with the matching name. If the name table ran short we still
    //    emit as many entries as we have names for (TOC trusted).
    const std::size_t have_names = std::min(static_cast<std::size_t>(count),
                                            name_list.size());
    r.entries.reserve(have_names);
    for (std::size_t i = 0; i < have_names; ++i) {
        const std::uint8_t* e = entries.data() + i * kEntryBytes;
        Entry en;
        std::uint32_t sz_or_flags = rd_u32le(e + 0);
        std::uint32_t res_type    = rd_u32le(e + 4);
        std::uint32_t off_block   = rd_u32le(e + 8);
        std::uint16_t used_blocks = rd_u16le(e + 12);
        std::uint16_t flags       = rd_u16le(e + 14);
        en.resource_type = res_type;
        en.is_rsc = (flags & (1 << 13)) != 0;
        en.rsc_flags = en.is_rsc ? sz_or_flags : 0;
        en.size = en.is_rsc
            ? static_cast<std::uint32_t>(used_blocks) * kSector
            : sz_or_flags;
        en.offset = static_cast<std::uint64_t>(off_block) * kSector;
        en.name = name_list[i];
        if (en.offset + en.size > bytes_size) {
            char msg[160];
            std::snprintf(msg, sizeof(msg),
                "entry '%s' (#%zu) extends past archive (%llu + %u > %zu)",
                en.name.c_str(), i,
                static_cast<unsigned long long>(en.offset),
                static_cast<unsigned>(en.size), bytes_size);
            r.error = msg;
            return r;
        }
        r.entries.push_back(std::move(en));
    }

    r.ok = true;
    return r;
}

}  // namespace thorbridge::img_v3
