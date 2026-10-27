#include "wtd.h"

#include <cstdio>
#include <cstring>

namespace thorbridge::wtd {

namespace {

std::uint16_t rd_u16le(const std::uint8_t* p) {
    return static_cast<std::uint16_t>(p[0] | (p[1] << 8));
}

std::uint32_t rd_u32le(const std::uint8_t* p) {
    return static_cast<std::uint32_t>(p[0]) |
           (static_cast<std::uint32_t>(p[1]) << 8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

// A plausible grcTexturePC format word is DXT1 or DXT5 and sits at a
// 4-byte aligned offset. The 4 bytes before (0x1C) are a (int16 w,
// int16 h) pair; both should be powers of two in the 32..2048 range
// for radar textures.
bool looks_like_power_of_two(int v) {
    return v >= 32 && v <= 4096 && (v & (v - 1)) == 0;
}

}  // namespace

Texture find_tile(const std::uint8_t* body, std::size_t n) {
    Texture r;
    if (!body || n < 0x50) {
        r.error = "inflated body too small for a grcTexturePC";
        return r;
    }

    // 1. Find the DXT1 / DXT5 fourcc at a 4-byte-aligned offset inside
    //    the system-memory section. For simple one-texture radar
    //    WTDs that's early, but 512x512 DXT5 fradar tiles push the
    //    struct past the first KB — scan the whole first half of the
    //    body minus one DXT1 block (an 8-byte alias would miss the
    //    graphics section's actual pixel bytes).
    std::size_t scan_end = n > 16 ? (n / 2) : n;
    if (scan_end > 8192) scan_end = 8192;
    std::size_t fmt_at = SIZE_MAX;
    dxt::Format fmt = dxt::Format::BC1;
    for (std::size_t off = 0; off + 4 <= scan_end; off += 4) {
        std::uint32_t word = rd_u32le(body + off);
        if (word != kD3dFmtDxt1 && word != kD3dFmtDxt3 && word != kD3dFmtDxt5) {
            continue;
        }
        if (off < 4) continue;  // need room for width/height before
        int w = rd_u16le(body + off - 4);
        int h = rd_u16le(body + off - 2);
        if (!looks_like_power_of_two(w)) continue;
        if (!looks_like_power_of_two(h)) continue;
        fmt_at = off;
        fmt = (word == kD3dFmtDxt1) ? dxt::Format::BC1
            : (word == kD3dFmtDxt3) ? dxt::Format::BC2
                                    : dxt::Format::BC3;
        r.width = w;
        r.height = h;
        break;
    }
    if (fmt_at == SIZE_MAX) {
        r.error = "no DXT1 / DXT5 fourcc with plausible dimensions found";
        return r;
    }
    r.format = fmt;

    // 2. The pixel data: the mip-0 slice sits at the END of the
    //    inflated buffer. For a mip chain, the LAST entry is the
    //    smallest mip; we skip past that for now by taking only the
    //    tail block of size level0_bytes.
    std::size_t level0 = dxt::compressed_size(fmt, r.width, r.height);
    if (level0 == 0 || level0 > n) {
        const char* fmt_name = fmt == dxt::Format::BC1 ? "BC1"
                         : fmt == dxt::Format::BC2 ? "BC2" : "BC3";
        char msg[128];
        std::snprintf(msg, sizeof(msg),
            "pixel size (%zu) implausible for buffer %zu bytes "
            "(w=%d h=%d fmt=%s)",
            level0, n, r.width, r.height, fmt_name);
        r.error = msg;
        return r;
    }
    // Mip chain: GTA IV textures often have multiple mip levels packed
    // after level 0. For radar tiles we only need level 0 (highest
    // resolution). Level 0 is the FIRST pixel block in the graphics
    // section, so its offset is `n - total_mip_bytes`. We don't know
    // the mip count without parsing the full struct, so we trust the
    // mip count field at the fourcc + 0x07 position (grcTexturePC
    // offset 0x27 relative to its own start, which is fmt_at - 0x20
    // from the fourcc).
    std::size_t struct_start = fmt_at - 0x20;
    int mip_count = static_cast<int>(body[struct_start + 0x27]);
    if (mip_count < 1) mip_count = 1;
    if (mip_count > 15) mip_count = 1;  // guard against junk

    std::size_t total = 0;
    int cur_w = r.width, cur_h = r.height;
    for (int i = 0; i < mip_count; ++i) {
        total += dxt::compressed_size(fmt, cur_w, cur_h);
        if (cur_w > 4) cur_w /= 2;
        if (cur_h > 4) cur_h /= 2;
    }
    if (total > n) {
        // Fall back to level 0 only.
        total = level0;
    }
    std::size_t pixel_offset = n - total;
    r.pixels = body + pixel_offset;
    r.pixel_bytes = level0;

    // 3. Texture name: m_Name is a 4-byte pointer at offset 0x14 of
    //    the struct. In a serialized resource it's a tagged offset
    //    into system memory; recovering the string is nice-to-have
    //    and non-blocking, so leave `name` empty.
    r.name.clear();

    r.ok = true;
    return r;
}

}  // namespace thorbridge::wtd
