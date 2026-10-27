// Host test for common/rpf7.cpp.
//
// Only the OPEN (plaintext) encryption path is exercised here. We
// ship decrypt-only for AES and NG, so the test harness can't
// synthesise encrypted TOCs without a parallel encrypt side we
// deliberately didn't build. The actual AES / NG decrypt paths are
// covered by crypto_test and ng_test respectively; rpf7's job is
// classification, field unpacking, name resolution, extraction, and
// recursive sub-archive descent, all of which run equally well on
// OPEN archives.
//
//   * Header parsing:         magic check, encryption field dispatch,
//                             entry_count / names_length bounds.
//   * Entry classification:   directory marker, bit-31 binary/resource
//                             split.
//   * Field unpacking:        packed uint64 for binary (name/size/
//                             offset layered), 24-bit LE fields for
//                             resources with the 0x80000000 bit
//                             stripped from the offset, 512-byte
//                             sector-to-byte scaling.
//   * Name resolution:        null-terminated strings in the names
//                             table are picked up correctly, with a
//                             defensive clip at 256 chars.
//   * Extraction:             plaintext bodies copied verbatim at
//                             the right offset for the right size,
//                             with binary entries using uncompressed
//                             size when FileSize == 0.
//   * Recursive descent:      a nested RPF built inside a parent
//                             archive's data region round-trips
//                             through a second open().
//   * find_in_directory:      case-insensitive match, miss returns
//                             nullptr, respects dir.entries_index +
//                             entries_count (not the whole archive).
//   * Error paths:            bad magic, truncated header, unknown
//                             encryption, out-of-range name_offset,
//                             out-of-range body, non-directory root.

#include "../rpf7.h"

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

using Enc = thorbridge::rpf7::Encryption;
using Kind = thorbridge::rpf7::EntryKind;

// Little-endian writers so the fixture matches the on-disk format
// without pulling in extra headers.
void push_u16(std::vector<std::uint8_t>& v, std::uint16_t x) {
    v.push_back(static_cast<std::uint8_t>(x & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 8) & 0xff));
}
void push_u24(std::vector<std::uint8_t>& v, std::uint32_t x) {
    v.push_back(static_cast<std::uint8_t>( x        & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >>  8) & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 16) & 0xff));
}
void push_u32(std::vector<std::uint8_t>& v, std::uint32_t x) {
    v.push_back(static_cast<std::uint8_t>( x        & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >>  8) & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 16) & 0xff));
    v.push_back(static_cast<std::uint8_t>((x >> 24) & 0xff));
}
void push_u64(std::vector<std::uint8_t>& v, std::uint64_t x) {
    for (int i = 0; i < 8; ++i) {
        v.push_back(static_cast<std::uint8_t>(x & 0xff));
        x >>= 8;
    }
}

// Pack a binary entry's uint64:
//   bits  0..15  name_offset
//   bits 16..39  file_size (bytes)
//   bits 40..63  file_offset_sectors
std::uint64_t pack_binary(std::uint16_t name_offset,
                          std::uint32_t file_size_bytes,
                          std::uint32_t file_offset_sectors) {
    return (static_cast<std::uint64_t>(name_offset) & 0xFFFFull) |
           ((static_cast<std::uint64_t>(file_size_bytes)       & 0xFFFFFFull) << 16) |
           ((static_cast<std::uint64_t>(file_offset_sectors)   & 0xFFFFFFull) << 40);
}

// Append a directory entry to the TOC blob.
void append_dir_entry(std::vector<std::uint8_t>& toc,
                      std::uint32_t name_off,
                      std::uint32_t entries_index,
                      std::uint32_t entries_count) {
    push_u32(toc, name_off);
    push_u32(toc, 0x7FFFFF00u);
    push_u32(toc, entries_index);
    push_u32(toc, entries_count);
}

// Append a binary file entry.
void append_binary_entry(std::vector<std::uint8_t>& toc,
                         std::uint16_t name_off,
                         std::uint32_t size_bytes,
                         std::uint32_t offset_sectors,
                         std::uint32_t uncompressed_size,
                         std::uint32_t enc_type) {
    push_u64(toc, pack_binary(name_off, size_bytes, offset_sectors));
    push_u32(toc, uncompressed_size);
    push_u32(toc, enc_type);
}

// Append a resource file entry. system_flags gets its bit-31 forced
// so the type classifier picks this as a resource.
void append_resource_entry(std::vector<std::uint8_t>& toc,
                           std::uint16_t name_off,
                           std::uint32_t size_bytes,
                           std::uint32_t offset_sectors,
                           std::uint32_t system_flags,
                           std::uint32_t graphics_flags) {
    push_u16(toc, name_off);
    push_u24(toc, size_bytes);
    push_u24(toc, offset_sectors & 0x7FFFFFu);
    // Resources are identified by bit-31 of the second uint32 being
    // set. The second uint32 is at offset 4 which spans the top byte
    // of the file-offset triplet plus the full system_flags uint32.
    // The real on-disk layout is "offset bytes + system_flags uint32"
    // and the resource bit is actually the top bit of byte 7 (the
    // last byte of the file-offset triplet). CodeWalker encodes it
    // by OR'ing 0x80 into byte 2 of the offset triplet during write.
    // Our push_u24 above pre-masks that bit, so we restore it here
    // by stuffing the resource marker into the offset's top bit,
    // which effectively lives in the system_flags lower-order byte.
    // Simpler: force bit 31 of the raw uint32 the reader will see as
    // "second" (the 32 bits spanning entry offsets 4..7). The entry
    // layout places the top byte of the offset into byte 7, so we
    // need the raw byte we wrote to carry 0x80. Redo it:
    //
    // That is: toc[toc.size()-1] holds the top byte of the offset
    // (which is what byte 7 of the entry becomes). Set its 0x80 bit.
    toc[toc.size() - 1] = static_cast<std::uint8_t>(toc[toc.size() - 1] | 0x80);
    push_u32(toc, system_flags);
    push_u32(toc, graphics_flags);
}

// Build an RPF7 archive out of in-memory parts and return the full
// archive bytes. The caller provides:
//   enc:        archive encryption flag
//   toc_bytes:  entry_count × 16 bytes of entry data, already packed
//   entry_count:same count as laid out in toc_bytes
//   names_blob: packed null-terminated name table
//   body:       raw body region placed after the TOC+names. Entries
//               with file_offset_sectors referencing this body region
//               must compute their sector so that
//               (sector * 512) == byte_offset_within_the_archive.
std::vector<std::uint8_t> build_rpf7(
        Enc enc, const std::vector<std::uint8_t>& toc_bytes,
        std::uint32_t entry_count,
        const std::vector<std::uint8_t>& names_blob,
        std::size_t body_start_offset,
        const std::vector<std::uint8_t>& body) {
    std::vector<std::uint8_t> out;
    // Header
    push_u32(out, thorbridge::rpf7::kMagic);
    push_u32(out, entry_count);
    push_u32(out, static_cast<std::uint32_t>(names_blob.size()));
    push_u32(out, static_cast<std::uint32_t>(enc));
    // TOC + names
    out.insert(out.end(), toc_bytes.begin(), toc_bytes.end());
    out.insert(out.end(), names_blob.begin(), names_blob.end());
    // Pad to body_start_offset
    if (out.size() > body_start_offset) {
        // Caller gave us an impossible layout.
        std::fprintf(stderr, "FIXTURE BUG: header+toc+names (%zu) > body_start (%zu)\n",
                     out.size(), body_start_offset);
        std::exit(2);
    }
    out.resize(body_start_offset, 0);
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

// --- Fixture: a simple OPEN archive with one directory, one binary
// file ("hello.txt" = "hello world"), one resource ("data.ytd" with
// 16 dummy bytes). Body region starts at sector 1 (offset 512). -----
struct SimpleArchive {
    std::vector<std::uint8_t> data;
    std::uint32_t hello_offset_sectors = 1;
    std::uint32_t data_offset_sectors  = 2;
    std::string hello_text   = "hello world";
    std::vector<std::uint8_t> data_body;  // 16 bytes
};

SimpleArchive make_simple_archive() {
    SimpleArchive s;
    s.data_body.assign(16, 0);
    for (int i = 0; i < 16; ++i) {
        s.data_body[i] = static_cast<std::uint8_t>(0xa0 + i);
    }

    // Names table:
    //   offset 0:  "" (root dir)
    //   offset 1:  "hello.txt"
    //   offset 11: "data.ytd"
    std::vector<std::uint8_t> names;
    const char* n0 = "";
    const char* n1 = "hello.txt";
    const char* n2 = "data.ytd";
    std::size_t n0_off = names.size();
    names.insert(names.end(), n0, n0 + std::strlen(n0) + 1);
    std::size_t n1_off = names.size();
    names.insert(names.end(), n1, n1 + std::strlen(n1) + 1);
    std::size_t n2_off = names.size();
    names.insert(names.end(), n2, n2 + std::strlen(n2) + 1);

    // Entries: root dir, hello.txt (binary), data.ytd (resource).
    std::vector<std::uint8_t> toc;
    append_dir_entry(toc,
                     static_cast<std::uint32_t>(n0_off),
                     /*entries_index=*/1,
                     /*entries_count=*/2);
    append_binary_entry(toc,
                        static_cast<std::uint16_t>(n1_off),
                        static_cast<std::uint32_t>(s.hello_text.size()),
                        s.hello_offset_sectors,
                        /*uncompressed=*/static_cast<std::uint32_t>(s.hello_text.size()),
                        /*enc_type=*/0);
    append_resource_entry(toc,
                          static_cast<std::uint16_t>(n2_off),
                          static_cast<std::uint32_t>(s.data_body.size()),
                          s.data_offset_sectors,
                          /*system_flags=*/0x12345678u,
                          /*graphics_flags=*/0xABCDEF01u);

    // Body region: start at sector 1 (offset 512). We stash
    // "hello world" at sector 1 and the resource bytes at sector 2.
    std::size_t body_start = 512;
    std::vector<std::uint8_t> body(512 + 16, 0);
    // First 512 bytes = sector 1 slot. First N bytes = hello text.
    std::memcpy(body.data(), s.hello_text.data(), s.hello_text.size());
    // Sector 2 slot = next 16 bytes.
    std::memcpy(body.data() + 512, s.data_body.data(), 16);

    s.data = build_rpf7(Enc::Open, toc, /*entry_count=*/3,
                        names, body_start, body);
    return s;
}

void test_open_simple_archive() {
    SimpleArchive s = make_simple_archive();
    thorbridge::rpf7::Archive a;
    bool ok = thorbridge::rpf7::open(
        s.data.data(), s.data.size(),
        /*self_name=*/"test.rpf", /*self_size=*/static_cast<std::uint32_t>(s.data.size()),
        /*keys=*/nullptr, a);
    CHECK(ok, "open() recovers header + TOC + names from an OPEN archive");
    CHECK(a.encryption == Enc::Open, "encryption flag parsed as OPEN");
    CHECK(a.entry_count == 3, "entry_count parsed");
    CHECK(a.entries.size() == 3, "all three entries materialised");

    CHECK(a.entries[0].kind == Kind::Directory,
          "entries[0] classified as directory (root)");
    CHECK(a.entries[1].kind == Kind::BinaryFile,
          "entries[1] classified as binary file (bit-31 clear)");
    CHECK(a.entries[2].kind == Kind::ResourceFile,
          "entries[2] classified as resource file (bit-31 set)");

    CHECK(a.entries[1].name == "hello.txt",
          "binary entry name resolved from names table");
    CHECK(a.entries[2].name == "data.ytd",
          "resource entry name resolved from names table");

    CHECK(a.entries[1].file_size_bytes == 11,
          "binary file size (bytes) unpacked from packed uint64");
    CHECK(a.entries[1].file_offset_bytes == 512,
          "binary file offset scaled from sectors to bytes");

    CHECK(a.entries[2].file_size_bytes == 16,
          "resource file size unpacked from 24-bit LE");
    CHECK(a.entries[2].file_offset_bytes == 1024,
          "resource file offset scaled from sectors to bytes");
    CHECK(a.entries[2].system_flags   == 0x12345678u,
          "resource system_flags preserved");
    CHECK(a.entries[2].graphics_flags == 0xABCDEF01u,
          "resource graphics_flags preserved");
}

void test_extract_binary_and_resource() {
    SimpleArchive s = make_simple_archive();
    thorbridge::rpf7::Archive a;
    bool ok = thorbridge::rpf7::open(
        s.data.data(), s.data.size(),
        "test.rpf", static_cast<std::uint32_t>(s.data.size()),
        nullptr, a);
    CHECK(ok, "open() ok for extract test");

    std::vector<std::uint8_t> body;
    CHECK(thorbridge::rpf7::extract(a, a.entries[1], nullptr, body),
          "extract plain binary entry");
    std::string got(reinterpret_cast<const char*>(body.data()), body.size());
    CHECK(got == "hello world",
          "extracted binary bytes match the body text");

    body.clear();
    CHECK(thorbridge::rpf7::extract(a, a.entries[2], nullptr, body),
          "extract plain resource entry");
    CHECK(body.size() == 16 && body[0] == 0xa0 && body[15] == 0xaf,
          "extracted resource bytes match the body region");
}

void test_find_in_directory_case_insensitive() {
    SimpleArchive s = make_simple_archive();
    thorbridge::rpf7::Archive a;
    thorbridge::rpf7::open(
        s.data.data(), s.data.size(),
        "test.rpf", static_cast<std::uint32_t>(s.data.size()),
        nullptr, a);
    const thorbridge::rpf7::Entry& root = a.entries[0];
    const auto* h1 = thorbridge::rpf7::find_in_directory(a, root, "hello.txt");
    const auto* h2 = thorbridge::rpf7::find_in_directory(a, root, "HELLO.TXT");
    const auto* h3 = thorbridge::rpf7::find_in_directory(a, root, "Hello.Txt");
    const auto* miss = thorbridge::rpf7::find_in_directory(a, root, "nope.txt");
    CHECK(h1 && h1 == h2 && h2 == h3,
          "find_in_directory is case-insensitive and returns the same pointer");
    CHECK(miss == nullptr,
          "find_in_directory returns nullptr for a non-existent entry");

    const auto* res = thorbridge::rpf7::find_in_directory(a, root, "data.YTD");
    CHECK(res && res->kind == Kind::ResourceFile,
          "find_in_directory returns the resource entry too");
}

void test_nested_rpf() {
    // Build a child archive, then build a parent that contains the
    // child's bytes verbatim as a binary entry's body.
    SimpleArchive child = make_simple_archive();

    // Parent names: "" (root), "child.rpf"
    std::vector<std::uint8_t> names;
    const char* n0 = "";
    const char* n1 = "child.rpf";
    std::size_t n0_off = names.size();
    names.insert(names.end(), n0, n0 + std::strlen(n0) + 1);
    std::size_t n1_off = names.size();
    names.insert(names.end(), n1, n1 + std::strlen(n1) + 1);

    // Parent entries: root dir + binary "child.rpf". The child's
    // body lives at sector 1 of the parent (offset 512).
    std::vector<std::uint8_t> toc;
    append_dir_entry(toc, static_cast<std::uint32_t>(n0_off), 1, 1);
    append_binary_entry(toc,
                        static_cast<std::uint16_t>(n1_off),
                        static_cast<std::uint32_t>(child.data.size()),
                        /*offset_sectors=*/1,
                        /*uncompressed=*/static_cast<std::uint32_t>(child.data.size()),
                        /*enc_type=*/0);

    std::size_t parent_body_start = 512;
    std::vector<std::uint8_t> body(parent_body_start - 32 + child.data.size(), 0);
    // Pad to sector 1 then stash the child archive.
    body.resize(child.data.size());
    std::memcpy(body.data(), child.data.data(), child.data.size());

    std::vector<std::uint8_t> parent_data =
        build_rpf7(Enc::Open, toc, /*entry_count=*/2, names,
                   parent_body_start, body);

    thorbridge::rpf7::Archive parent;
    CHECK(thorbridge::rpf7::open(parent_data.data(), parent_data.size(),
                                  "parent.rpf",
                                  static_cast<std::uint32_t>(parent_data.size()),
                                  nullptr, parent),
          "open parent archive");
    CHECK(parent.entries.size() == 2,
          "parent archive has 2 entries (root + child.rpf)");

    const auto* child_entry = thorbridge::rpf7::find_in_directory(
        parent, parent.entries[0], "child.rpf");
    CHECK(child_entry && child_entry->kind == Kind::BinaryFile,
          "child.rpf found as a binary entry under root");

    std::vector<std::uint8_t> child_bytes;
    CHECK(thorbridge::rpf7::extract(parent, *child_entry, nullptr, child_bytes),
          "extract child archive bytes from parent");
    CHECK(child_bytes.size() == child.data.size() &&
          std::memcmp(child_bytes.data(), child.data.data(), child.data.size()) == 0,
          "extracted child bytes match the embedded child archive verbatim");

    thorbridge::rpf7::Archive nested;
    CHECK(thorbridge::rpf7::open(child_bytes.data(), child_bytes.size(),
                                  "child.rpf",
                                  static_cast<std::uint32_t>(child_bytes.size()),
                                  nullptr, nested),
          "open the nested archive from the extracted bytes");
    CHECK(nested.entries.size() == 3,
          "nested archive has 3 entries (root + binary + resource)");
    const auto* hello = thorbridge::rpf7::find_in_directory(
        nested, nested.entries[0], "hello.txt");
    CHECK(hello != nullptr,
          "nested archive still carries its original hello.txt entry");
}

void test_errors() {
    SimpleArchive s = make_simple_archive();

    // Bad magic.
    {
        auto data = s.data;
        data[0] ^= 0x01;
        thorbridge::rpf7::Archive a;
        CHECK(!thorbridge::rpf7::open(data.data(), data.size(),
                                       "test.rpf", 0, nullptr, a),
              "open refuses an archive with wrong RPF7 magic");
    }
    // Truncated header.
    {
        thorbridge::rpf7::Archive a;
        CHECK(!thorbridge::rpf7::open(s.data.data(), 8,
                                       "test.rpf", 0, nullptr, a),
              "open refuses an archive smaller than the 16-byte header");
    }
    // Unknown encryption flag.
    {
        auto data = s.data;
        data[12] = 0x42;  // not any of NONE/OPEN/AES/NG
        data[13] = 0x42;
        data[14] = 0x42;
        data[15] = 0x42;
        thorbridge::rpf7::Archive a;
        CHECK(!thorbridge::rpf7::open(data.data(), data.size(),
                                       "test.rpf", 0, nullptr, a),
              "open refuses an archive with unknown encryption id");
    }
    // Null archive data.
    {
        thorbridge::rpf7::Archive a;
        CHECK(!thorbridge::rpf7::open(nullptr, 1024, "x", 0, nullptr, a),
              "open refuses a null data pointer");
    }
    // Extracting a directory is nonsense.
    {
        thorbridge::rpf7::Archive a;
        thorbridge::rpf7::open(s.data.data(), s.data.size(),
                                "test.rpf", 0, nullptr, a);
        std::vector<std::uint8_t> body;
        CHECK(!thorbridge::rpf7::extract(a, a.entries[0], nullptr, body),
              "extract refuses a directory entry");
    }
}

}  // namespace

int main() {
    test_open_simple_archive();
    test_extract_binary_and_resource();
    test_find_in_directory_case_insensitive();
    test_nested_rpf();
    test_errors();
    if (failures) {
        std::fprintf(stderr, "\n%d rpf7 check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all rpf7 checks passed.\n");
    return 0;
}
