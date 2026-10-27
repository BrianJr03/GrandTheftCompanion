#include "rsc7.h"

#include <cstring>

#include "inflate.h"

namespace thorbridge::rsc7 {

namespace {

std::uint32_t read_u32_le(const std::uint8_t* p) {
    return  static_cast<std::uint32_t>(p[0])        |
           (static_cast<std::uint32_t>(p[1]) <<  8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

}  // namespace

std::uint32_t size_from_flags(std::uint32_t flags) {
    // Nine power-of-two counters at bit positions (27, 26, 25, 24,
    // 17..23, 11..16, 7..10, 5..6, 4), scaled by (1, 2, 4, 8, 16, 32,
    // 64, 128, 256) respectively. Then multiplied by base_size =
    // 0x200 << ss, where ss = flags & 0xF.
    std::uint32_t s0 = ((flags >> 27) & 0x1u)  << 0;   // *1
    std::uint32_t s1 = ((flags >> 26) & 0x1u)  << 1;   // *2
    std::uint32_t s2 = ((flags >> 25) & 0x1u)  << 2;   // *4
    std::uint32_t s3 = ((flags >> 24) & 0x1u)  << 3;   // *8
    std::uint32_t s4 = ((flags >> 17) & 0x7Fu) << 4;   // *16
    std::uint32_t s5 = ((flags >> 11) & 0x3Fu) << 5;   // *32
    std::uint32_t s6 = ((flags >>  7) & 0x0Fu) << 6;   // *64
    std::uint32_t s7 = ((flags >>  5) & 0x03u) << 7;   // *128
    std::uint32_t s8 = ((flags >>  4) & 0x1u)  << 8;   // *256
    std::uint32_t ss = (flags & 0xFu);
    std::uint32_t base_size = 0x200u << ss;
    return base_size * (s0 + s1 + s2 + s3 + s4 + s5 + s6 + s7 + s8);
}

bool parse_header(const std::uint8_t* data, std::size_t data_bytes,
                  Header& out) {
    out = Header{};
    if (!data || data_bytes < kHeaderBytes) return false;
    std::uint32_t magic = read_u32_le(data + 0);
    if (magic != kMagic) return false;
    out.version        = read_u32_le(data + 4);
    out.system_flags   = read_u32_le(data + 8);
    out.graphics_flags = read_u32_le(data + 12);
    out.system_size    = size_from_flags(out.system_flags);
    out.graphics_size  = size_from_flags(out.graphics_flags);
    out.system_type    = (out.system_flags   >> 28) & 0xFu;
    out.graphics_type  = (out.graphics_flags >> 28) & 0xFu;
    return true;
}

bool decompress_body(const std::uint8_t* body, std::size_t body_bytes,
                     std::vector<std::uint8_t>& out) {
    out.clear();
    if (!body || body_bytes == 0) return false;
    // Raw deflate, no zlib wrapper, no trailing Adler check. Matches
    // .NET DeflateStream, which is what CodeWalker calls at
    // ExtractFileResource / DecompressBytes.
    std::vector<std::uint8_t> staged;
    bool ok = inflate::decompress(body, body_bytes, staged,
                                  /*expect_zlib=*/false,
                                  /*check_adler=*/false);
    if (ok) {
        out = std::move(staged);
        return true;
    }
    // Fallback per CodeWalker's ExtractFileResource line 617-626:
    // if decompression fails the body is handed back verbatim.
    out.assign(body, body + body_bytes);
    return true;
}

bool open(const std::uint8_t* data, std::size_t data_bytes,
          Header& header_out, std::vector<std::uint8_t>& payload_out) {
    payload_out.clear();
    if (!parse_header(data, data_bytes, header_out)) return false;
    const std::uint8_t* body = data + kHeaderBytes;
    std::size_t body_bytes = data_bytes - kHeaderBytes;
    if (body_bytes == 0) {
        // Valid header but no body. Payload stays empty; still OK.
        return true;
    }
    return decompress_body(body, body_bytes, payload_out);
}

}  // namespace thorbridge::rsc7
