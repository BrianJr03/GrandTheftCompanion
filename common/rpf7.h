#pragma once

// GTA V RPF7 archive reader. Parses the TOC + names table and
// exposes per-entry metadata so callers can walk the archive and
// extract specific entries. Decryption is driven by the archive's
// `encryption` field in combination with (name, size) context:
//
//   NONE / OPEN   plaintext TOC + plaintext entry bodies.
//   AES           AES-256-ECB with 1 round, keyed by the global
//                 AES key located inside GTA5.exe (keys_v::aes_key).
//   NG            the 17-round NG cipher (common/ng.cpp), with the
//                 per-file 272-byte key picked from the 101-key pool
//                 via (name_hash + size + 61) % 101 where name_hash
//                 comes from the LUT-driven hash (common/jenk.cpp).
//
// Layout, per Neodymium's RpfFile.cs in CodeWalker (MIT):
//
//   Header (16 bytes, little-endian):
//     +00 version        uint32  == 0x52504637 ("RPF7")
//     +04 entry_count    uint32
//     +08 names_length   uint32
//     +0C encryption     uint32  (see enum above)
//
//   Entry table (entry_count × 16 bytes). Entry kind is deduced from
//   the second uint32:
//     == 0x7FFFFF00                           -> directory
//     (second uint32 & 0x80000000) == 0       -> binary file
//     else                                    -> resource file
//
//   Directory entry (16 bytes LE):
//     +00 name_offset   uint32
//     +04 marker        uint32  == 0x7FFFFF00
//     +08 entries_index uint32
//     +0C entries_count uint32
//
//   Binary file entry (16 bytes LE):
//     +00 packed       uint64  bits  0-15  name_offset
//                              bits 16-39  file_size (bytes)
//                              bits 40-63  file_offset (512-byte sectors)
//     +08 uncompressed uint32  size of entry after optional inflate
//     +0C enc_type     uint32  0 = plain, 1 = archive's encryption
//
//   Resource file entry (16 bytes LE):
//     +00 name_offset   uint16
//     +02 file_size     3 bytes LE (0xFFFFFF means "look at RSC7 header")
//     +05 file_offset   3 bytes LE, low 23 bits only (sectors)
//     +08 system_flags  uint32
//     +0C graphics_flags uint32
//
//   Names table: packed null-terminated byte strings; each entry's
//   name_offset is a byte offset into this blob.
//
// File offsets expressed in SECTORS of 512 bytes each. Sizes always in
// bytes.
//
// This module parses the TOC and resolves entry names. It does NOT
// decompress resources (rsc7 lands separately) and it does NOT
// recursively descend nested RPFs. The caller does that explicitly
// via a second rpf7::open() call on the extracted sub-archive bytes.
// Keeping the recursion explicit matches how the atlas worker uses it:
// "find the one .rpf entry I want, extract it, open as a new archive."

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace thorbridge::keys_v { struct ExtractedKeys; }

namespace thorbridge::rpf7 {

constexpr std::uint32_t kMagic           = 0x52504637u;  // 'RPF7' LE
constexpr std::size_t   kSectorBytes     = 512;
constexpr std::size_t   kHeaderBytes     = 16;
constexpr std::size_t   kEntryBytes      = 16;
constexpr std::uint32_t kDirectoryMarker = 0x7FFFFF00u;

enum class Encryption : std::uint32_t {
    None = 0x00000000u,
    Open = 0x4E45504Fu,
    Aes  = 0x0FFFFFF9u,
    Ng   = 0x0FEFFFFFu,
};

enum class EntryKind : std::uint8_t {
    Directory,
    BinaryFile,
    ResourceFile,
};

struct Entry {
    EntryKind     kind;
    std::uint32_t name_offset;
    std::string   name;

    // Directory entries.
    std::uint32_t entries_index  = 0;
    std::uint32_t entries_count  = 0;

    // File entries (binary and resource).
    std::uint64_t file_offset_bytes = 0;   // already multiplied by 512
    std::uint32_t file_size_bytes   = 0;

    // Binary file entries.
    std::uint32_t file_uncompressed_size = 0;
    std::uint32_t binary_encryption_type = 0;  // 0 = plain, 1 = archive-encryption

    // Resource file entries.
    std::uint32_t system_flags   = 0;
    std::uint32_t graphics_flags = 0;
    // Resources ending in .ysc are per-entry NG encrypted; everything
    // else is plain inside the archive.
    bool          resource_is_encrypted = false;
};

struct Archive {
    // The caller owns `data`; Archive just holds references into it.
    const std::uint8_t* data        = nullptr;
    std::size_t         data_bytes  = 0;

    // Archive-scope context required for NG derivation.
    std::string         self_name;         // lowercased by caller is fine
    std::uint32_t       self_size_bytes = 0;

    // Parsed header.
    Encryption          encryption      = Encryption::None;
    std::uint32_t       entry_count     = 0;
    std::uint32_t       names_length    = 0;

    // Flat entry list in on-disk order. Entries[0] is the root
    // directory; its entries_index + entries_count locate direct
    // children, and nested directories are reached by recursing.
    std::vector<Entry>  entries;
};

// Parse the TOC. `data` must point at the start of the archive and
// cover at least `data_bytes`. `self_name` and `self_size_bytes`
// supply the NG derivation context (ignored for NONE / OPEN / AES).
// `keys` is the retail key pool; may be nullptr if the archive is
// NONE or OPEN.
bool open(const std::uint8_t* data, std::size_t data_bytes,
          const char* self_name, std::uint32_t self_size_bytes,
          const keys_v::ExtractedKeys* keys,
          Archive& out);

// Extract an entry's bytes into `out`. For binary entries with
// encryption_type == 1 and the archive encrypted (AES or NG), the
// body is decrypted using the archive-level scheme (AES: global key;
// NG: entry.name + entry.file_size_bytes as the derivation seeds, per
// CodeWalker's extract path). For plain or unencrypted entries, the
// body is copied verbatim. `out` is resized to the right length.
//
// Resource entries are returned as-is; parsing the RSC7 header and
// optional inflate is a job for rsc7/ytd. The caller sees the raw
// bytes starting from the resource's file_offset_bytes.
//
// Returns false on bad offset/size, unknown encryption, or missing
// key material.
bool extract(const Archive& archive, const Entry& entry,
             const keys_v::ExtractedKeys* keys,
             std::vector<std::uint8_t>& out);

// Convenience: find the first entry (not recursive) under `dir`
// whose name matches `name` case-insensitively. Returns a pointer
// into archive.entries, or nullptr if nothing matches.
const Entry* find_in_directory(const Archive& archive, const Entry& dir,
                               const char* name);

}  // namespace thorbridge::rpf7
