#pragma once

// atlas:: — in-plugin extractor for GTA IV's radar texture archive.
//
// On atlas_extract the worker thread finds pc/textures/radar.img inside
// the user's game folder, parses its TOC, reads each entry, and pushes
// one PNG per tile over a dedicated binary socket at 127.0.0.1:47802.
// The main 47801 JSON control channel carries progress frames so the
// companion can show percentage / state / cancel.
//
// Threading contract — hard rules from CLAUDE.md:
//   * NO native calls from this thread. All work is file I/O + CPU
//     decoding; the atlas worker must never touch game memory.
//   * The 47802 listener is created only while an extraction is active
//     and torn down when the worker exits. One connection per run.
//   * DLL_PROCESS_DETACH must set the cancel flag and join the worker.

#include <cstddef>
#include <cstdint>
#include <string>

namespace atlas {

enum class State : std::uint8_t {
    Idle,
    Scanning,     // walking the game-folder tree for radar.img
    Reading,      // archive opened, TOC walked, decoding tiles
    Sending,      // socket connected, streaming PNGs
    Done,
    Error,
};

struct Progress {
    std::uint32_t ver;        // bumps on every mutation
    State         state;
    std::uint8_t  pct;
    std::uint16_t tiles;      // total tile count once discovered
    std::uint16_t cols;
    std::uint16_t rows;
    std::uint16_t tile_w;
    std::uint16_t tile_h;
    // Human-readable last error, UTF-8, "" on success.
    char          err[128];
};

// Call once at plugin startup. Initialises the critical section used
// to publish Progress between the worker thread and bridge sender.
void init();

// Called on DLL_PROCESS_DETACH. Signals cancel and joins the worker,
// then tears down the critical section. Safe to call even if start()
// was never invoked.
void shutdown();

// Starts a new extraction. Returns false if one is already in flight.
bool start();

// Signals the running worker to stop at the next check-point. Safe to
// call when nothing is running (no-op).
void cancel();

// Snapshot of the latest progress. Returns true if `ver` moved since
// `last_ver_in_out`; `last_ver_in_out` is updated to the current ver.
// The bridge sender uses this to decide whether to emit a new frame.
bool read_progress(Progress& out, std::uint32_t& last_ver_in_out);

}  // namespace atlas
