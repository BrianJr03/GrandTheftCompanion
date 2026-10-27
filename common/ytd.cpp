#include "ytd.h"

#include <cctype>
#include <cstring>

namespace thorbridge::ytd {

namespace {

constexpr std::uint64_t kOffsetMask       = 0x0FFFFFFFull;  // low 28 bits
constexpr std::size_t   kTextureDictBytes = 64;
constexpr std::size_t   kTextureFullBytes = 144;   // 0x90

std::uint16_t read_u16_le(const std::uint8_t* p) {
    return static_cast<std::uint16_t>(
        static_cast<std::uint16_t>(p[0]) |
        (static_cast<std::uint16_t>(p[1]) << 8));
}

std::uint32_t read_u32_le(const std::uint8_t* p) {
    return  static_cast<std::uint32_t>(p[0])        |
           (static_cast<std::uint32_t>(p[1]) <<  8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

std::uint64_t read_u64_le(const std::uint8_t* p) {
    std::uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

// Resolve a 64-bit resource pointer to a flat offset into the
// `payload` buffer. Pointers encode which memory section they belong
// to in the top nibble: 0x5 for system, 0x6 for graphics. The low 28
// bits are the byte offset within that section. We also require the
// pointed-at region of `needed_bytes` to fit entirely inside the
// section so a corrupt pointer cannot read past the payload.
bool resolve_pointer(std::uint64_t ptr, std::size_t needed_bytes,
                     std::size_t system_size, std::size_t graphics_size,
                     std::size_t& out_offset) {
    std::uint32_t top = static_cast<std::uint32_t>((ptr >> 28) & 0xFu);
    std::uint64_t off = ptr & kOffsetMask;
    if (top == 0x5u) {
        if (off > system_size || off + needed_bytes > system_size) return false;
        out_offset = static_cast<std::size_t>(off);
        return true;
    }
    if (top == 0x6u) {
        if (off > graphics_size || off + needed_bytes > graphics_size) return false;
        out_offset = system_size + static_cast<std::size_t>(off);
        return true;
    }
    return false;
}

// Null-terminated ASCII, defensively capped to CodeWalker's 256-char
// ceiling. The byte at `start` must exist; we walk forward until we
// hit a zero or run out of section bytes.
bool read_cstring(const std::uint8_t* payload, std::size_t start_offset,
                  std::size_t section_end, std::string& out) {
    if (start_offset >= section_end) return false;
    const std::uint8_t* p = payload + start_offset;
    std::size_t max_scan = section_end - start_offset;
    if (max_scan > 256) max_scan = 256;
    std::size_t len = 0;
    while (len < max_scan && p[len] != 0) ++len;
    if (len == max_scan && (section_end - start_offset) > 256) {
        // Hit the 256-char defensive cap before a null terminator;
        // CodeWalker clips names there too.
    } else if (len == max_scan) {
        // Walked off the end without finding a terminator.
        return false;
    }
    out.assign(reinterpret_cast<const char*>(p), len);
    return true;
}

bool iequals(const std::string& a, const char* b) {
    if (!b) return false;
    std::size_t i = 0;
    for (; i < a.size() && b[i] != '\0'; ++i) {
        unsigned char ca = static_cast<unsigned char>(a[i]);
        unsigned char cb = static_cast<unsigned char>(b[i]);
        if (ca >= 'A' && ca <= 'Z') ca = static_cast<unsigned char>(ca - 'A' + 'a');
        if (cb >= 'A' && cb <= 'Z') cb = static_cast<unsigned char>(cb - 'A' + 'a');
        if (ca != cb) return false;
    }
    return i == a.size() && b[i] == '\0';
}

}  // namespace

std::uint32_t full_mip_size(std::uint32_t stride, std::uint32_t height,
                            std::uint8_t levels) {
    std::uint32_t total = 0;
    std::uint32_t cur   = stride * height;
    for (std::uint8_t lv = 0; lv < levels; ++lv) {
        total += cur;
        cur /= 4;
    }
    return total;
}

bool open(const std::uint8_t* payload, std::size_t system_size,
          std::size_t graphics_size, Dictionary& out) {
    out = Dictionary{};
    if (!payload || system_size < kTextureDictBytes) return false;

    out.payload       = payload;
    out.system_size   = system_size;
    out.graphics_size = graphics_size;

    // TextureDictionary lives at system offset 0.
    const std::uint8_t* dict = payload;

    // Offsets 0x20..0x2F: TextureNameHashes (uint32 array).
    std::uint64_t name_hashes_ptr = read_u64_le(dict + 0x20);
    std::uint16_t name_hashes_count = read_u16_le(dict + 0x28);

    // Offsets 0x30..0x3F: Textures (uint64 pointer array).
    std::uint64_t textures_ptr   = read_u64_le(dict + 0x30);
    std::uint16_t textures_count = read_u16_le(dict + 0x38);
    std::uint16_t textures_cap   = read_u16_le(dict + 0x3A);

    if (name_hashes_count != textures_count) return false;
    if (textures_count == 0) return true;  // empty but valid

    std::size_t nh_off = 0;
    if (!resolve_pointer(name_hashes_ptr,
                         static_cast<std::size_t>(name_hashes_count) * 4u,
                         system_size, graphics_size, nh_off)) {
        return false;
    }
    const std::uint8_t* name_hashes = payload + nh_off;

    std::size_t tp_off = 0;
    if (!resolve_pointer(textures_ptr,
                         static_cast<std::size_t>(textures_cap) * 8u,
                         system_size, graphics_size, tp_off)) {
        return false;
    }
    const std::uint8_t* texture_ptrs = payload + tp_off;

    out.textures.resize(textures_count);
    for (std::size_t i = 0; i < textures_count; ++i) {
        Texture& t = out.textures[i];
        t.name_hash = read_u32_le(name_hashes + i * 4);

        std::uint64_t tx_ptr = read_u64_le(texture_ptrs + i * 8);
        std::size_t tx_off = 0;
        if (!resolve_pointer(tx_ptr, kTextureFullBytes,
                             system_size, graphics_size, tx_off)) {
            return false;
        }
        const std::uint8_t* tx = payload + tx_off;

        // TextureBase: NamePointer at +0x28.
        std::uint64_t name_ptr = read_u64_le(tx + 0x28);

        // Texture-specific fields:
        t.width         = read_u16_le(tx + 0x50);
        t.height        = read_u16_le(tx + 0x52);
        t.depth         = read_u16_le(tx + 0x54);
        t.stride        = read_u16_le(tx + 0x56);
        t.format        = static_cast<TextureFormat>(read_u32_le(tx + 0x58));
        t.levels        = tx[0x5D];
        t.data_pointer  = read_u64_le(tx + 0x70);

        if (name_ptr != 0) {
            std::size_t n_off = 0;
            if (!resolve_pointer(name_ptr, 1,
                                 system_size, graphics_size, n_off)) {
                return false;
            }
            // Names should live in system memory. If the pointer
            // escapes into graphics memory, that is still legal per
            // the raw format but is uncommon in retail; let it slide.
            std::size_t section_end =
                (n_off < system_size) ? system_size : (system_size + graphics_size);
            if (!read_cstring(payload, n_off, section_end, t.name)) {
                return false;
            }
        }

        // Pixel data size + offset in graphics memory.
        std::uint32_t mip0_bytes =
            static_cast<std::uint32_t>(t.stride) *
            static_cast<std::uint32_t>(t.height);
        std::uint32_t full_bytes = full_mip_size(t.stride, t.height, t.levels);
        if (full_bytes > 0) {
            std::size_t data_off = 0;
            if (!resolve_pointer(t.data_pointer, full_bytes,
                                 system_size, graphics_size, data_off)) {
                return false;
            }
            t.mip0_offset_bytes      = static_cast<std::uint32_t>(data_off);
            t.mip0_size_bytes        = mip0_bytes;
            t.full_data_offset_bytes = static_cast<std::uint32_t>(data_off);
            t.full_data_size_bytes   = full_bytes;
        }
    }

    return true;
}

const Texture* find(const Dictionary& dict, const char* name) {
    if (!name) return nullptr;
    for (const Texture& t : dict.textures) {
        if (iequals(t.name, name)) return &t;
    }
    return nullptr;
}

}  // namespace thorbridge::ytd
