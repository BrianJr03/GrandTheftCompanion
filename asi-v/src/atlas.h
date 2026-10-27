#pragma once

// atlas:: in-plugin extractor for GTA V's minimap. First-pass is a
// PROBE: find GTA5.exe, locate the retail key / table material inside
// it via keys_v, walk every .rpf under the game dir to log their
// contents, drill into scaleform_minimap.rpf, log every ytd + its
// textures. This commit does NOT stitch tiles, write v.png, or emit
// v.json; the real decoder lands in a follow-up once the on-device
// probe log tells us the exact archive path + ytd layout the user's
// install ships.
//
// Threading contract (same as asi/src/atlas.h):
//   * NO Script Hook V natives from this thread. All work is file I/O
//     + CPU decoding; the atlas worker must never touch game memory.
//   * DLL_PROCESS_DETACH sets the cancel flag and joins the worker
//     with a 2 s timeout.

#include <cstddef>
#include <cstdint>

namespace atlas {

enum class State : std::uint8_t {
    Idle,
    Scanning,     // walking <game dir> for GTA5.exe + .rpf files
    Reading,      // keys located, archives being inspected
    Sending,      // reserved for later (streaming PNGs over 47802)
    Done,
    Error,
};

struct Progress {
    std::uint32_t ver;        // bumps on every mutation
    State         state;
    std::uint8_t  pct;
    std::uint16_t tiles;      // reserved; always 0 in probe mode
    std::uint16_t cols;       // reserved
    std::uint16_t rows;       // reserved
    std::uint16_t tile_w;     // reserved
    std::uint16_t tile_h;     // reserved
    // Human-readable last error / status, UTF-8, "" on success.
    char          err[128];
};

void init();
void shutdown();
bool start();
void cancel();
bool read_progress(Progress& out, std::uint32_t& last_ver_in_out);

}  // namespace atlas
