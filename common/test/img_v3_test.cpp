// Host test for common/img_v3.cpp. We don't have an encrypt helper
// (we never encrypt real archives), but AES-256-ECB is self-inverse
// under *round reversal*: applying decrypt N times then decrypt N
// times again returns to the original ciphertext. Rather than
// implement encryption, the test writes an archive, decrypts it 16x
// to "deposit" the plaintext, then re-decrypts 16x to produce a
// ciphertext whose parse round-trip recovers the plaintext. (The
// cover is that `parse_toc` runs `decrypt_ecb(..., 16)` on each
// region; so feeding it the output of our own `decrypt_ecb(..., 16)`
// applied to the plaintext yields the plaintext back.)

#include "../aes.h"
#include "../img_v3.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
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
    out.push_back(static_cast<std::uint8_t>(v & 0xff));
    out.push_back(static_cast<std::uint8_t>((v >>  8) & 0xff));
    out.push_back(static_cast<std::uint8_t>((v >> 16) & 0xff));
    out.push_back(static_cast<std::uint8_t>((v >> 24) & 0xff));
}
void put_u16le(std::vector<std::uint8_t>& out, std::uint16_t v) {
    out.push_back(static_cast<std::uint8_t>(v & 0xff));
    out.push_back(static_cast<std::uint8_t>((v >>  8) & 0xff));
}

struct FakeEntry {
    std::string name;
    std::vector<std::uint8_t> body;
};

// Build an archive whose encrypted regions, when decrypted 16x by the
// img_v3 parser, yield the plaintext laid out below. We "pre-apply"
// 16 forward decrypts to the plaintext so a decrypt_ecb(..., 16) on
// the archive leaves the regions in their plaintext state.
std::vector<std::uint8_t> build_archive(
    const std::vector<FakeEntry>& items,
    const std::uint8_t key[32]) {
    thorbridge::aes::Schedule sched{};
    thorbridge::aes::key_schedule(key, sched);

    const std::uint32_t count = static_cast<std::uint32_t>(items.size());

    // Pack filename table (NUL-terminated, back-to-back).
    std::vector<std::uint8_t> names_plain;
    for (const auto& it : items) {
        names_plain.insert(names_plain.end(),
                           it.name.begin(), it.name.end());
        names_plain.push_back(0);
    }
    // Pad names to a 16-byte block so the whole thing is encryptable
    // without a trailing-partial-plain case for this test.
    while ((names_plain.size() % 16) != 0) names_plain.push_back(0);

    const std::size_t entries_offset = 20;
    const std::size_t entries_bytes  = count * 16;
    const std::size_t names_offset   = entries_offset + entries_bytes;
    const std::size_t names_bytes    = names_plain.size();
    const std::size_t toc_end        = names_offset + names_bytes;

    // Lay out plaintext header (first 16 bytes to be encrypted) +
    // plain middle (4 bytes) + plaintext entries + plaintext names.
    std::vector<std::uint8_t> arch;
    arch.reserve(toc_end + items.size() * kSector + kSector);
    // Encrypted header region bytes 0..15 (plaintext at this stage).
    put_u32le(arch, 0xA94E2A52);           // magic
    put_u32le(arch, 3);                    // version
    put_u32le(arch, count);                // item_count
    put_u32le(arch, static_cast<std::uint32_t>(toc_end));  // table_size
    // Plain middle (bytes 16..19).
    put_u16le(arch, 0x0010);               // item_size
    put_u16le(arch, 0x00E9);               // unknown

    // Entries (plaintext at this stage). Assign offsets starting at
    // the next sector boundary past toc_end.
    std::uint32_t next_sector = static_cast<std::uint32_t>(
        (toc_end + kSector - 1) / kSector);
    std::vector<std::pair<std::uint32_t, std::uint32_t>> placements;
    for (const auto& it : items) {
        std::uint32_t off_block   = next_sector;
        std::uint32_t used_blocks = static_cast<std::uint32_t>(
            (it.body.size() + kSector - 1) / kSector);
        placements.push_back({off_block, used_blocks});
        next_sector += used_blocks;
        put_u32le(arch, static_cast<std::uint32_t>(it.body.size())); // size
        put_u32le(arch, 0);                                          // resource_type
        put_u32le(arch, off_block);                                  // offset_block
        put_u16le(arch, static_cast<std::uint16_t>(used_blocks));    // used_blocks
        put_u16le(arch, 0);                                          // flags (not RSC)
    }
    // Names.
    arch.insert(arch.end(), names_plain.begin(), names_plain.end());

    // Now encrypt the three regions. We own only `decrypt_block`; the
    // trick is that running decrypt_ecb an extra 16 times on the
    // plaintext produces a ciphertext C such that parse_toc's
    // decrypt_ecb(C, 16) recovers the plaintext. In other words, we
    // deliberately mis-use decrypt as "encrypt" by running the same
    // transform twice: once here, once in the parser. Each region is
    // decrypted exactly 16 times here, totalling 32 decrypts across
    // the pipeline, which is a round-trip identity only if we also
    // apply the counter-operation. We don't have encrypt, so this
    // round-trip is NOT identity — instead, the test does something
    // different: it bakes an "encrypt" by using the parser's decrypt
    // as the inverse of itself, which it is NOT. So this is wrong.
    //
    // Fix: we need a real forward encrypt. For a round-trip test we
    // implement a tiny AES-256 encrypt_block right here in the test
    // using the standard tables; the decrypt-only module we ship
    // stays unchanged.
    //
    // See encrypt_block below.
    (void)sched;
    return arch;  // caller finishes by appending bodies and encrypting
}

// --- Minimal AES-256 encrypt_block for test use only --------------------

constexpr std::uint8_t kSBox[256] = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16,
};
// Key schedule reused from the production module via key_schedule() —
// no need to duplicate Rcon here.
std::uint8_t xtime_t(std::uint8_t v) {
    std::uint8_t h = static_cast<std::uint8_t>(-((v >> 7) & 1));
    return static_cast<std::uint8_t>((v << 1) ^ (h & 0x1b));
}
void sub_bytes_t(std::uint8_t s[16]) {
    for (int i = 0; i < 16; ++i) s[i] = kSBox[s[i]];
}
void shift_rows_t(std::uint8_t s[16]) {
    std::uint8_t t;
    t = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t;
    t = s[2]; s[2] = s[10]; s[10] = t;
    t = s[6]; s[6] = s[14]; s[14] = t;
    t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;
}
void mix_columns_t(std::uint8_t s[16]) {
    for (int c = 0; c < 4; ++c) {
        std::uint8_t* col = s + c * 4;
        std::uint8_t a0 = col[0], a1 = col[1], a2 = col[2], a3 = col[3];
        std::uint8_t x0 = xtime_t(a0), x1 = xtime_t(a1),
                     x2 = xtime_t(a2), x3 = xtime_t(a3);
        col[0] = static_cast<std::uint8_t>(x0 ^ x1 ^ a1 ^ a2 ^ a3);
        col[1] = static_cast<std::uint8_t>(a0 ^ x1 ^ x2 ^ a2 ^ a3);
        col[2] = static_cast<std::uint8_t>(a0 ^ a1 ^ x2 ^ x3 ^ a3);
        col[3] = static_cast<std::uint8_t>(x0 ^ a0 ^ a1 ^ a2 ^ x3);
    }
}
void add_rk_t(std::uint8_t s[16], const std::uint8_t* k) {
    for (int i = 0; i < 16; ++i) s[i] ^= k[i];
}
void encrypt_block_test(const thorbridge::aes::Schedule& sch,
                        std::uint8_t block[16]) {
    add_rk_t(block, sch.rk);
    for (int round = 1; round < 14; ++round) {
        sub_bytes_t(block);
        shift_rows_t(block);
        mix_columns_t(block);
        add_rk_t(block, sch.rk + round * 16);
    }
    sub_bytes_t(block);
    shift_rows_t(block);
    add_rk_t(block, sch.rk + 14 * 16);
}

void encrypt_ecb_test(const thorbridge::aes::Schedule& sch,
                      std::uint8_t* data, std::size_t bytes, int rounds) {
    std::size_t full = bytes & ~std::size_t{15};
    for (std::size_t off = 0; off < full; off += 16) {
        for (int r = 0; r < rounds; ++r) encrypt_block_test(sch, data + off);
    }
}

// Confirm encrypt + decrypt round-trip (test vector + round-trip only).
void test_encrypt_decrypt_round_trip() {
    std::uint8_t key[32];
    for (int i = 0; i < 32; ++i) key[i] = static_cast<std::uint8_t>(i);
    thorbridge::aes::Schedule sch{};
    thorbridge::aes::key_schedule(key, sch);
    std::uint8_t data[48];
    for (int i = 0; i < 48; ++i) data[i] = static_cast<std::uint8_t>(0xa0 + i);
    std::uint8_t copy[48];
    std::memcpy(copy, data, 48);
    encrypt_ecb_test(sch, data, 48, 16);
    CHECK(std::memcmp(data, copy, 48) != 0, "16x encrypt mutates data");
    thorbridge::aes::decrypt_ecb(sch, data, 48, 16);
    CHECK(std::memcmp(data, copy, 48) == 0,
          "16x decrypt(16x encrypt(x)) == x for AES-256-ECB");
}

std::vector<std::uint8_t> build_archive_full(
    const std::vector<FakeEntry>& items,
    const std::uint8_t key[32]) {
    thorbridge::aes::Schedule sch{};
    thorbridge::aes::key_schedule(key, sch);

    // Phase 1: lay out the plaintext TOC exactly as parse_toc expects
    // AFTER its decrypt step. Then encrypt the three regions in place
    // using the test's forward AES-256.
    std::vector<std::uint8_t> arch = build_archive(items, key);  // plaintext stub

    // Overwrite the plaintext regions with ciphertext.
    const std::uint32_t count = static_cast<std::uint32_t>(items.size());

    // Compute names block (padded) exactly as build_archive did.
    std::vector<std::uint8_t> names_plain;
    for (const auto& it : items) {
        names_plain.insert(names_plain.end(), it.name.begin(), it.name.end());
        names_plain.push_back(0);
    }
    while ((names_plain.size() % 16) != 0) names_plain.push_back(0);

    const std::size_t entries_offset = 20;
    const std::size_t entries_bytes  = count * 16;
    const std::size_t names_offset   = entries_offset + entries_bytes;

    // Encrypt bytes 0..15.
    encrypt_ecb_test(sch, arch.data(), 16, 16);
    // Encrypt entries.
    encrypt_ecb_test(sch, arch.data() + entries_offset, entries_bytes, 16);
    // Encrypt the full-block part of the names table.
    std::size_t full_names = names_plain.size() & ~std::size_t{15};
    if (full_names) {
        encrypt_ecb_test(sch, arch.data() + names_offset, full_names, 16);
    }

    // Phase 2: pad up to the first file-body sector, then append each
    // file body.
    std::size_t toc_end = names_offset + names_plain.size();
    std::size_t first_body = (toc_end + kSector - 1) & ~(kSector - 1);
    arch.resize(first_body, 0);
    for (const auto& it : items) {
        std::size_t padded = (it.body.size() + kSector - 1) & ~(kSector - 1);
        std::size_t at = arch.size();
        arch.resize(at + padded, 0);
        if (!it.body.empty()) std::memcpy(arch.data() + at, it.body.data(),
                                          it.body.size());
    }
    return arch;
}

void test_img_v3_round_trip() {
    std::uint8_t key[32];
    for (int i = 0; i < 32; ++i) key[i] = static_cast<std::uint8_t>(0x11 + i);

    std::vector<FakeEntry> items;
    items.push_back({"radar00.wtd",
                     std::vector<std::uint8_t>(100, 0xA1)});
    items.push_back({"radar01.wtd",
                     std::vector<std::uint8_t>(2200, 0xB2)});
    items.push_back({"radar63.wtd",
                     std::vector<std::uint8_t>(42, 0xC3)});
    auto arch = build_archive_full(items, key);

    auto r = thorbridge::img_v3::parse_toc(arch.data(), arch.size(), key);
    CHECK(r.ok, "img_v3 parse_toc returns ok");
    if (!r.ok) {
        std::fprintf(stderr, "  (parse error: %s)\n", r.error.c_str());
    }
    CHECK(r.item_count == 3, "item_count reads back as 3");
    CHECK(r.entries.size() == 3, "three entries recovered");
    if (r.entries.size() == 3) {
        CHECK(r.entries[0].name == "radar00.wtd", "entry 0 name");
        CHECK(r.entries[1].name == "radar01.wtd", "entry 1 name");
        CHECK(r.entries[2].name == "radar63.wtd", "entry 2 name");
        CHECK(r.entries[0].size == 100, "entry 0 size");
        CHECK(r.entries[1].size == 2200, "entry 1 size");
        CHECK(r.entries[2].size == 42, "entry 2 size");
        // Verify we can actually read a byte of body data at the
        // reported offset.
        CHECK(arch[r.entries[0].offset] == 0xA1, "body 0 at reported offset");
        CHECK(arch[r.entries[1].offset] == 0xB2, "body 1 at reported offset");
        CHECK(arch[r.entries[2].offset] == 0xC3, "body 2 at reported offset");
    }
}

void test_img_v3_rejects_wrong_key() {
    std::uint8_t key[32];
    for (int i = 0; i < 32; ++i) key[i] = static_cast<std::uint8_t>(0x11 + i);
    std::vector<FakeEntry> items = {
        {"radar00.wtd", std::vector<std::uint8_t>(10, 0x55)}};
    auto arch = build_archive_full(items, key);

    std::uint8_t wrong[32];
    for (int i = 0; i < 32; ++i) wrong[i] = static_cast<std::uint8_t>(0x99);
    auto r = thorbridge::img_v3::parse_toc(arch.data(), arch.size(), wrong);
    CHECK(!r.ok, "parse_toc refuses with the wrong key");
    CHECK(r.error.find("bad IMG v3 header") != std::string::npos,
          "error names the magic / header mismatch");
}

}  // namespace

int main() {
    test_encrypt_decrypt_round_trip();
    test_img_v3_round_trip();
    test_img_v3_rejects_wrong_key();
    if (failures) {
        std::fprintf(stderr, "\n%d img_v3 check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all img_v3 checks passed.\n");
    return 0;
}
