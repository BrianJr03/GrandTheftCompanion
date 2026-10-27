#include "rpf7.h"

#include <algorithm>
#include <cctype>
#include <cstring>

#include "aes.h"
#include "jenk.h"
#include "keys_v.h"
#include "ng.h"

namespace thorbridge::rpf7 {

namespace {

// Lowercase ASCII copy for case-insensitive compare.
std::string ascii_lower(const char* s) {
    std::string out;
    if (!s) return out;
    for (; *s; ++s) {
        unsigned char c = static_cast<unsigned char>(*s);
        if (c >= 'A' && c <= 'Z') c = static_cast<unsigned char>(c - 'A' + 'a');
        out.push_back(static_cast<char>(c));
    }
    return out;
}

std::uint32_t read_u32_le(const std::uint8_t* p) {
    return  static_cast<std::uint32_t>(p[0])        |
           (static_cast<std::uint32_t>(p[1]) <<  8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

std::uint16_t read_u16_le(const std::uint8_t* p) {
    return static_cast<std::uint16_t>(
        static_cast<std::uint16_t>(p[0]) |
        (static_cast<std::uint16_t>(p[1]) << 8));
}

std::uint64_t read_u64_le(const std::uint8_t* p) {
    std::uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

std::uint32_t read_u24_le(const std::uint8_t* p) {
    return  static_cast<std::uint32_t>(p[0])        |
           (static_cast<std::uint32_t>(p[1]) <<  8) |
           (static_cast<std::uint32_t>(p[2]) << 16);
}

// Decrypt a buffer in place with AES-256-ECB, 1 round, using the
// global AES key from keys_v.
bool decrypt_aes_block(const keys_v::ExtractedKeys& keys,
                       std::uint8_t* data, std::size_t bytes) {
    aes::Schedule s{};
    aes::key_schedule(keys.aes_key, s);
    aes::decrypt_ecb(s, data, bytes, 1);
    return true;
}

// Decrypt a buffer in place with the NG cipher. The key is picked
// from the 101-key pool using (name_hash + size + 61) % 101, where
// name_hash is Rockstar's LUT-driven filename hash.
bool decrypt_ng_block(const keys_v::ExtractedKeys& keys,
                      const std::string& name, std::uint32_t size,
                      std::uint8_t* data, std::size_t bytes) {
    std::uint32_t name_hash = jenk::hash(
        reinterpret_cast<const std::uint8_t*>(name.data()),
        name.size(), keys.lut);
    const std::uint8_t* raw_key =
        ng::pick_file_key(name_hash, size, keys.ng_keys);
    if (!raw_key) return false;
    ng::Key k{};
    if (!ng::load_key(raw_key, ng::kKeyBytes, k)) return false;
    ng::Tables t{};
    if (!ng::load_tables(keys.ng_decrypt_tables, ng::kTablesBytes, t)) {
        return false;
    }
    ng::decrypt_ecb(k, t, data, bytes);
    return true;
}

bool decrypt_toc_buffer(Encryption enc, const std::string& self_name,
                        std::uint32_t self_size,
                        const keys_v::ExtractedKeys* keys,
                        std::uint8_t* data, std::size_t bytes) {
    switch (enc) {
        case Encryption::None:
        case Encryption::Open:
            return true;
        case Encryption::Aes:
            if (!keys) return false;
            return decrypt_aes_block(*keys, data, bytes);
        case Encryption::Ng:
            if (!keys) return false;
            return decrypt_ng_block(*keys, self_name, self_size, data, bytes);
    }
    return false;
}

// Try to resolve a null-terminated string from the names table at
// `off`. Returns true on success and fills `out`. The namesLength
// bound is inclusive of the terminating null for the LAST entry only;
// we treat anything that walks off the end as an error.
bool read_name_at(const std::uint8_t* names, std::size_t names_bytes,
                  std::uint32_t off, std::string& out) {
    if (off >= names_bytes) return false;
    const std::uint8_t* start = names + off;
    const std::uint8_t* end   = names + names_bytes;
    const std::uint8_t* p     = start;
    while (p < end && *p != 0) ++p;
    if (p == end) return false;  // never-terminated string
    std::size_t len = static_cast<std::size_t>(p - start);
    // Cap at CodeWalker's defensive 256-char ceiling to match the
    // "long names can freeze the RPFExplorer" guard in RpfFile.cs.
    if (len > 256) len = 256;
    out.assign(reinterpret_cast<const char*>(start), len);
    return true;
}

// Classify an entry by its second uint32 per CodeWalker.
EntryKind classify_entry(std::uint32_t second_u32) {
    if (second_u32 == kDirectoryMarker) return EntryKind::Directory;
    if ((second_u32 & 0x80000000u) == 0) return EntryKind::BinaryFile;
    return EntryKind::ResourceFile;
}

bool parse_entry(const std::uint8_t* raw, Entry& e) {
    std::uint32_t second = read_u32_le(raw + 4);
    e.kind = classify_entry(second);
    switch (e.kind) {
        case EntryKind::Directory: {
            e.name_offset   = read_u32_le(raw + 0);
            // raw + 4 is the marker; already matched.
            e.entries_index = read_u32_le(raw + 8);
            e.entries_count = read_u32_le(raw + 12);
            return true;
        }
        case EntryKind::BinaryFile: {
            std::uint64_t packed = read_u64_le(raw + 0);
            e.name_offset  = static_cast<std::uint32_t>(packed & 0xFFFFu);
            std::uint32_t file_size =
                static_cast<std::uint32_t>((packed >> 16) & 0xFFFFFFu);
            std::uint32_t file_offset_sectors =
                static_cast<std::uint32_t>((packed >> 40) & 0xFFFFFFu);
            e.file_size_bytes       = file_size;
            e.file_offset_bytes     =
                static_cast<std::uint64_t>(file_offset_sectors) * kSectorBytes;
            e.file_uncompressed_size = read_u32_le(raw + 8);
            e.binary_encryption_type = read_u32_le(raw + 12);
            if (e.binary_encryption_type != 0 &&
                e.binary_encryption_type != 1) {
                return false;
            }
            return true;
        }
        case EntryKind::ResourceFile: {
            e.name_offset = read_u16_le(raw + 0);
            e.file_size_bytes = read_u24_le(raw + 2);
            std::uint32_t file_offset_sectors =
                read_u24_le(raw + 5) & 0x7FFFFFu;
            e.file_offset_bytes =
                static_cast<std::uint64_t>(file_offset_sectors) * kSectorBytes;
            e.system_flags   = read_u32_le(raw + 8);
            e.graphics_flags = read_u32_le(raw + 12);
            return true;
        }
    }
    return false;
}

bool is_known_encryption(std::uint32_t raw) {
    return raw == static_cast<std::uint32_t>(Encryption::None) ||
           raw == static_cast<std::uint32_t>(Encryption::Open) ||
           raw == static_cast<std::uint32_t>(Encryption::Aes)  ||
           raw == static_cast<std::uint32_t>(Encryption::Ng);
}

}  // namespace

bool open(const std::uint8_t* data, std::size_t data_bytes,
          const char* self_name, std::uint32_t self_size_bytes,
          const keys_v::ExtractedKeys* keys,
          Archive& out) {
    out = Archive{};
    if (!data || data_bytes < kHeaderBytes) return false;

    std::uint32_t version      = read_u32_le(data + 0);
    std::uint32_t entry_count  = read_u32_le(data + 4);
    std::uint32_t names_length = read_u32_le(data + 8);
    std::uint32_t enc_raw      = read_u32_le(data + 12);
    if (version != kMagic) return false;
    if (!is_known_encryption(enc_raw)) return false;

    std::size_t entries_bytes = static_cast<std::size_t>(entry_count) * kEntryBytes;
    std::size_t header_total  = kHeaderBytes + entries_bytes + names_length;
    if (header_total > data_bytes) return false;

    out.data            = data;
    out.data_bytes      = data_bytes;
    out.self_name       = self_name ? self_name : "";
    out.self_size_bytes = self_size_bytes;
    out.encryption      = static_cast<Encryption>(enc_raw);
    out.entry_count     = entry_count;
    out.names_length    = names_length;

    std::vector<std::uint8_t> entries_buf(entries_bytes);
    std::vector<std::uint8_t> names_buf(names_length);
    if (entries_bytes > 0) {
        std::memcpy(entries_buf.data(), data + kHeaderBytes, entries_bytes);
    }
    if (names_length > 0) {
        std::memcpy(names_buf.data(),
                    data + kHeaderBytes + entries_bytes, names_length);
    }
    if (!decrypt_toc_buffer(out.encryption, out.self_name, out.self_size_bytes,
                            keys, entries_buf.data(), entries_bytes)) {
        return false;
    }
    if (!decrypt_toc_buffer(out.encryption, out.self_name, out.self_size_bytes,
                            keys, names_buf.data(), names_length)) {
        return false;
    }

    out.entries.resize(entry_count);
    for (std::uint32_t i = 0; i < entry_count; ++i) {
        Entry& e = out.entries[i];
        if (!parse_entry(entries_buf.data() + i * kEntryBytes, e)) return false;
        if (!read_name_at(names_buf.data(), names_length, e.name_offset, e.name)) {
            return false;
        }
        if (e.kind == EntryKind::ResourceFile) {
            std::string lower = ascii_lower(e.name.c_str());
            e.resource_is_encrypted = lower.size() >= 4 &&
                lower.compare(lower.size() - 4, 4, ".ysc") == 0;
        }
    }

    // CodeWalker treats entries[0] as the root directory. If it is
    // not a directory the archive is malformed.
    if (entry_count == 0 || out.entries[0].kind != EntryKind::Directory) {
        return false;
    }

    return true;
}

bool extract(const Archive& archive, const Entry& entry,
             const keys_v::ExtractedKeys* keys,
             std::vector<std::uint8_t>& out) {
    out.clear();
    if (!archive.data) return false;
    if (entry.kind == EntryKind::Directory) return false;

    // Figure out byte length to copy and whether the entry body
    // is itself encrypted at the archive level.
    std::uint32_t size_bytes = 0;
    bool entry_encrypted = false;
    if (entry.kind == EntryKind::BinaryFile) {
        // CodeWalker: size_bytes == 0 means the on-disk body size
        // equals FileUncompressedSize (uncompressed, not deflated).
        size_bytes = entry.file_size_bytes != 0
            ? entry.file_size_bytes
            : entry.file_uncompressed_size;
        entry_encrypted = (entry.binary_encryption_type == 1);
    } else {
        // Resource entry. We hand back the raw resource bytes
        // including its RSC7 header; parsing that is rsc7's job.
        size_bytes = entry.file_size_bytes;
        entry_encrypted = entry.resource_is_encrypted;
    }
    if (size_bytes == 0) return false;

    std::uint64_t end = entry.file_offset_bytes +
                        static_cast<std::uint64_t>(size_bytes);
    if (end > archive.data_bytes) return false;

    out.assign(archive.data + entry.file_offset_bytes,
               archive.data + entry.file_offset_bytes + size_bytes);

    if (!entry_encrypted) return true;

    switch (archive.encryption) {
        case Encryption::None:
        case Encryption::Open:
            // Entry claims encryption but the archive has none;
            // CodeWalker treats this as a corrupt entry.
            out.clear();
            return false;
        case Encryption::Aes:
            if (!keys) { out.clear(); return false; }
            return decrypt_aes_block(*keys, out.data(), out.size());
        case Encryption::Ng:
            if (!keys) { out.clear(); return false; }
            return decrypt_ng_block(*keys, entry.name, size_bytes,
                                    out.data(), out.size());
    }
    out.clear();
    return false;
}

const Entry* find_in_directory(const Archive& archive, const Entry& dir,
                               const char* name) {
    if (dir.kind != EntryKind::Directory || !name) return nullptr;
    std::string want = ascii_lower(name);
    std::uint32_t start = dir.entries_index;
    std::uint32_t end   = dir.entries_index + dir.entries_count;
    if (end > archive.entries.size()) return nullptr;
    for (std::uint32_t i = start; i < end; ++i) {
        const Entry& e = archive.entries[i];
        if (ascii_lower(e.name.c_str()) == want) return &e;
    }
    return nullptr;
}

}  // namespace thorbridge::rpf7
