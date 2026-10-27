#include "rsc.h"

#include <cstdio>

#include "inflate.h"

namespace thorbridge::rsc {

namespace {

std::uint32_t rd_u32le(const std::uint8_t* p) {
    return static_cast<std::uint32_t>(p[0]) |
           (static_cast<std::uint32_t>(p[1]) << 8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

}  // namespace

Inflated inflate(const std::uint8_t* bytes, std::size_t n) {
    Inflated r;
    if (!bytes || n < 16) {
        r.error = "RSC entry too small for a 12-byte header";
        return r;
    }
    std::uint32_t magic = rd_u32le(bytes);
    if (magic != kMagic) {
        char msg[80];
        std::snprintf(msg, sizeof(msg),
                      "bad RSC magic 0x%08X (expected 0x%08X)",
                      magic, kMagic);
        r.error = msg;
        return r;
    }
    r.resource_version = rd_u32le(bytes + 4);
    r.flags            = rd_u32le(bytes + 8);
    const std::uint8_t* z = bytes + 12;
    std::size_t z_bytes  = n - 12;
    // RSC entries live inside sector-rounded IMG v3 slots, so the
    // actual zlib stream ends somewhere before `z + z_bytes` and the
    // Adler-32 trailer is NOT at the end of this buffer. Skip the
    // trailer check; the DEFLATE walker will stop cleanly at the
    // BFINAL=1 block header.
    if (!thorbridge::inflate::decompress(z, z_bytes, r.body,
                                          /*expect_zlib=*/true,
                                          /*check_adler=*/false)) {
        r.error = "zlib inflate of RSC body failed";
        return r;
    }
    r.ok = true;
    return r;
}

}  // namespace thorbridge::rsc
