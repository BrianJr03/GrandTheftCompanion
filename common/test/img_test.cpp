// Host-side test for common/img.cpp. Builds a synthetic VER2 archive in
// memory, then confirms the TOC walker returns the expected entry list.
// Also covers the two refusal paths: non-VER2 magic and a TOC whose
// stated size extends past the archive.

#include "../img.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond, msg)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            ++failures;                                                    \
            std::fprintf(stderr, "FAIL: %s (%s:%d)\n", msg, __FILE__,      \
                         __LINE__);                                        \
        }                                                                  \
    } while (0)

constexpr std::size_t kSector = 2048;

void put_u32le(std::vector<std::uint8_t>& out, std::uint32_t v) {
    out.push_back(static_cast<std::uint8_t>(v & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >>  8) & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >> 24) & 0xFF));
}

void put_u16le(std::vector<std::uint8_t>& out, std::uint16_t v) {
    out.push_back(static_cast<std::uint8_t>(v & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >>  8) & 0xFF));
}

std::vector<std::uint8_t> build_archive(
    const std::vector<std::pair<std::string, std::vector<std::uint8_t>>>& items) {
    // Layout: header + N 32-byte entries, then entry bodies at 2048-byte
    // sector boundaries.
    std::uint32_t count = static_cast<std::uint32_t>(items.size());
    std::size_t toc_bytes = 8 + 32 * count;
    std::size_t first_data_sector = (toc_bytes + kSector - 1) / kSector;

    std::vector<std::uint8_t> buf;
    buf.insert(buf.end(), {'V', 'E', 'R', '2'});
    put_u32le(buf, count);

    std::size_t next_sector = first_data_sector;
    std::vector<std::pair<std::size_t, std::size_t>> placements;
    for (const auto& it : items) {
        std::size_t sect = next_sector;
        std::size_t sz_sect = (it.second.size() + kSector - 1) / kSector;
        placements.push_back({sect, sz_sect});
        next_sector += sz_sect;
        put_u32le(buf, static_cast<std::uint32_t>(sect));
        put_u16le(buf, static_cast<std::uint16_t>(sz_sect));
        put_u16le(buf, 0);
        char name[24] = {0};
        std::strncpy(name, it.first.c_str(), sizeof(name));
        buf.insert(buf.end(), name, name + sizeof(name));
    }
    // Pad up to first_data_sector * kSector.
    buf.resize(first_data_sector * kSector, 0);
    // Append each body (sector-padded).
    for (std::size_t i = 0; i < items.size(); ++i) {
        buf.insert(buf.end(), items[i].second.begin(), items[i].second.end());
        std::size_t padded = placements[i].second * kSector;
        buf.resize(buf.size() + (padded - items[i].second.size()), 0);
    }
    return buf;
}

void test_round_trip() {
    std::vector<std::pair<std::string, std::vector<std::uint8_t>>> items;
    items.push_back({"radar00.wtd", std::vector<std::uint8_t>(100, 0xA1)});
    items.push_back({"radar01.wtd", std::vector<std::uint8_t>(2100, 0xB2)});
    items.push_back({"radar02.wtd", std::vector<std::uint8_t>(50, 0xC3)});
    auto arch = build_archive(items);

    auto r = thorbridge::img::parse_toc(arch.data(), arch.size());
    CHECK(r.ok, "VER2 parse_toc returns ok");
    CHECK(r.entries.size() == 3, "all three entries recovered");
    if (r.entries.size() == 3) {
        CHECK(r.entries[0].name == "radar00.wtd", "entry 0 name");
        CHECK(r.entries[1].name == "radar01.wtd", "entry 1 name");
        CHECK(r.entries[2].name == "radar02.wtd", "entry 2 name");
        // Content check: read offset + first byte.
        for (std::size_t i = 0; i < 3; ++i) {
            const auto& e = r.entries[i];
            std::uint8_t marker = static_cast<std::uint8_t>(0xA1 + i * 0x11);
            CHECK(arch[e.offset] == marker,
                  "entry body starts with expected marker");
        }
    }
}

void test_rejects_bad_magic() {
    std::vector<std::uint8_t> arch(1024, 0);
    arch[0] = 'V'; arch[1] = 'E'; arch[2] = 'R'; arch[3] = '3';
    auto r = thorbridge::img::parse_toc(arch.data(), arch.size());
    CHECK(!r.ok, "non-VER2 magic refused");
    CHECK(r.error.find("VER2") != std::string::npos,
          "error names the expected magic");
    CHECK(!r.head_hex.empty(), "head_hex populated for diagnostics");
}

void test_rejects_truncated() {
    std::vector<std::uint8_t> arch;
    arch.insert(arch.end(), {'V', 'E', 'R', '2'});
    // claim 10 entries, but the archive isn't long enough for them.
    put_u32le(arch, 10);
    auto r = thorbridge::img::parse_toc(arch.data(), arch.size());
    CHECK(!r.ok, "truncated TOC refused");
}

}  // namespace

int main() {
    test_round_trip();
    test_rejects_bad_magic();
    test_rejects_truncated();
    if (failures) {
        std::fprintf(stderr, "\n%d img check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all img checks passed.\n");
    return 0;
}
