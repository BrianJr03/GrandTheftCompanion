#pragma once

// GTA V YTD (texture dictionary) walker. Takes a decompressed RSC7
// payload (output of rsc7::open) and exposes per-texture metadata +
// a byte range into the full-mip pixel data so callers can feed the
// bytes to common/dxt.cpp.
//
// Layout, per Neodymium's Texture.cs + ResourceBaseTypes.cs in
// CodeWalker (MIT):
//
//   TextureDictionary (64 bytes, lives at system offset 0):
//     +00  ResourceFileBase (16 bytes, VFT + unknowns + pages info ptr)
//     +10  4 x uint32 unknowns (16 bytes)
//     +20  ResourceSimpleList64_uint TextureNameHashes:
//            +20  entries_ptr (uint64)   -> array of uint32 name hashes
//            +28  count       (uint16)
//            +2A  capacity    (uint16)
//            +2C  padding     (4 bytes)
//     +30  ResourcePointerList64<Texture> Textures:
//            +30  entries_ptr (uint64)   -> array of uint64 Texture ptrs
//            +38  count       (uint16)
//            +3A  capacity    (uint16)
//            +3C  padding     (4 bytes)
//
//   Texture (144 bytes = TextureBase 80 + Texture-specific 64):
//     TextureBase (0x00..0x4F):
//       +28  NamePointer (uint64)        -> null-terminated string
//     Texture-specific (0x50..0x8F):
//       +50  Width       (uint16)
//       +52  Height      (uint16)
//       +54  Depth       (uint16, usually 1)
//       +56  Stride      (uint16, bytes per row at mip 0)
//       +58  Format      (uint32, D3DFMT_* fourcc or small enum)
//       +5D  Levels      (uint8, mip count including mip 0)
//       +70  DataPointer (uint64)        -> graphics-memory pixel bytes
//
//   Pointer encoding (same convention as CodeWalker's
//   ResourceDataReader): the top nibble of a 64-bit pointer picks
//   which memory section it refers to, and the low 28 bits are the
//   byte offset within that section:
//     0x50000000 | off  -> system memory,   payload[off]
//     0x60000000 | off  -> graphics memory, payload[system_size + off]
//
//   Pixel data for a texture is a mip chain: `stride * height` bytes
//   for mip 0, `stride * height / 4` for mip 1, etc.
//
// This module parses the dictionary metadata and resolves pointers
// into flat offsets into the payload. It does NOT decompress DXT1 /
// DXT3 / DXT5 payloads; the caller does that via common/dxt.cpp.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace thorbridge::ytd {

// D3DFMT fourcc / enum values that Rockstar uses for YTD textures.
// Matches TextureFormat in Texture.cs line 1125.
enum class TextureFormat : std::uint32_t {
    A8R8G8B8 = 21,
    X8R8G8B8 = 22,
    A1R5G5B5 = 25,
    A8       = 28,
    A8B8G8R8 = 32,
    L8       = 50,
    DXT1     = 0x31545844u,  // 'DXT1' LE
    DXT3     = 0x33545844u,  // 'DXT3' LE
    DXT5     = 0x35545844u,  // 'DXT5' LE
    ATI1     = 0x31495441u,  // 'ATI1' LE
    ATI2     = 0x32495441u,  // 'ATI2' LE
    BC7      = 0x20374342u,  // 'BC7 ' LE
};

struct Texture {
    std::string   name;
    std::uint32_t name_hash   = 0;
    std::uint16_t width       = 0;
    std::uint16_t height      = 0;
    std::uint16_t depth       = 0;   // usually 1 for 2D textures
    std::uint16_t stride      = 0;   // bytes per row at mip 0
    TextureFormat format      = TextureFormat::DXT1;
    std::uint8_t  levels      = 0;   // mip count including mip 0
    std::uint64_t data_pointer = 0;  // raw ptr (0x60000000 | off) for debugging
    // Byte range of mip 0's pixels inside the payload (flat offset).
    std::uint32_t mip0_offset_bytes = 0;
    std::uint32_t mip0_size_bytes   = 0;
    // Full-mip-chain byte range (mip 0 + mip 1 + ...).
    std::uint32_t full_data_offset_bytes = 0;
    std::uint32_t full_data_size_bytes   = 0;
};

struct Dictionary {
    // Caller owns payload; Dictionary holds a view.
    const std::uint8_t* payload = nullptr;
    std::size_t system_size   = 0;
    std::size_t graphics_size = 0;
    std::vector<Texture> textures;
};

// Parse a decompressed YTD payload. `system_size` is the byte length
// of the system memory segment (which starts at payload[0]), and
// `graphics_size` is the byte length of the graphics memory segment
// (which starts at payload[system_size]). Both sizes come from the
// RSC7 header. Returns false on bad input, out-of-range pointer, or
// inconsistent TOC. On success `out.textures` holds a slot per
// texture with its name + metadata + full-mip byte range.
bool open(const std::uint8_t* payload, std::size_t system_size,
          std::size_t graphics_size, Dictionary& out);

// Find a texture by case-insensitive name. Returns nullptr on miss.
const Texture* find(const Dictionary& dict, const char* name);

// Compute the full-mip byte length for a texture descriptor. Public
// so tests + the atlas worker can size buffers without touching the
// Texture struct directly.
std::uint32_t full_mip_size(std::uint32_t stride, std::uint32_t height,
                            std::uint8_t levels);

}  // namespace thorbridge::ytd
