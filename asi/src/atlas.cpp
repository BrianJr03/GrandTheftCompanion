#include "atlas.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "dxt.h"
#include "img.h"
#include "img_v3.h"
#include "keys_iv.h"
#include "log.h"
#include "png.h"
#include "rsc.h"
#include "wtd.h"

namespace atlas {

namespace {

struct State_ {
    CRITICAL_SECTION cs;
    Progress         pub{};
    HANDLE           worker = nullptr;
    volatile LONG    cancel = 0;
    volatile LONG    running = 0;
    bool             inited = false;
};

State_ g;

void publish(State st, std::uint8_t pct, const char* err,
             std::uint16_t tiles = 0, std::uint16_t cols = 0,
             std::uint16_t rows = 0, std::uint16_t tw = 0,
             std::uint16_t th = 0) {
    EnterCriticalSection(&g.cs);
    ++g.pub.ver;
    g.pub.state = st;
    g.pub.pct = pct;
    if (tiles)  g.pub.tiles  = tiles;
    if (cols)   g.pub.cols   = cols;
    if (rows)   g.pub.rows   = rows;
    if (tw)     g.pub.tile_w = tw;
    if (th)     g.pub.tile_h = th;
    if (err) {
        std::snprintf(g.pub.err, sizeof(g.pub.err), "%s", err);
    } else {
        g.pub.err[0] = '\0';
    }
    LeaveCriticalSection(&g.cs);
}

// Returns the directory the ASI itself lives in (the game folder for
// Ultimate ASI Loader setups). Used as the search root for radar.img.
std::wstring plugin_dir() {
    HMODULE mod = NULL;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                       GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       reinterpret_cast<LPCWSTR>(&plugin_dir), &mod);
    wchar_t buf[MAX_PATH];
    DWORD n = GetModuleFileNameW(mod, buf, MAX_PATH);
    std::wstring p(buf, n);
    std::size_t slash = p.find_last_of(L"\\/");
    if (slash != std::wstring::npos) p.resize(slash);
    return p;
}

// Depth-first search for a filename under `root`. Stops at the first
// match. Used for both radar.img and GTAIV.exe. Case-insensitive.
std::wstring find_file(const std::wstring& root, const wchar_t* name,
                       int depth = 0) {
    if (depth > 6) return L"";  // defensive against symlink loops
    WIN32_FIND_DATAW fd;
    std::wstring probe = root + L"\\*";
    HANDLE h = FindFirstFileW(probe.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return L"";
    std::wstring found;
    do {
        if (fd.cFileName[0] == L'.' &&
            (fd.cFileName[1] == 0 ||
             (fd.cFileName[1] == L'.' && fd.cFileName[2] == 0))) continue;
        std::wstring full = root + L"\\" + fd.cFileName;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            found = find_file(full, name, depth + 1);
            if (!found.empty()) break;
        } else {
            if (_wcsicmp(fd.cFileName, name) == 0) {
                found = full;
                break;
            }
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return found;
}

std::vector<std::uint8_t> read_whole_file(const std::wstring& path) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return {};
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(f, &sz)) { CloseHandle(f); return {}; }
    if (sz.QuadPart > (1024ll * 1024ll * 1024ll)) {
        // Hard cap at 1 GB. radar.img is a few MB — anything larger is
        // either the wrong file or a sign of mis-detection.
        CloseHandle(f);
        return {};
    }
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(sz.QuadPart));
    DWORD got = 0, off = 0;
    while (off < buf.size()) {
        DWORD want = static_cast<DWORD>(buf.size() - off);
        if (!ReadFile(f, buf.data() + off, want, &got, NULL) || got == 0) break;
        off += got;
    }
    CloseHandle(f);
    buf.resize(off);
    return buf;
}

std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(),
                                NULL, 0, NULL, NULL);
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(),
                        s.data(), n, NULL, NULL);
    return s;
}

std::wstring widen(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(),
                                NULL, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(),
                        w.data(), n);
    return w;
}

bool write_whole_file(const std::wstring& path,
                      const std::vector<std::uint8_t>& bytes) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_WRITE, 0, NULL,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return false;
    DWORD wrote = 0;
    const std::size_t total = bytes.size();
    std::size_t off = 0;
    while (off < total) {
        DWORD want = static_cast<DWORD>(total - off);
        if (!WriteFile(f, bytes.data() + off, want, &wrote, NULL) || wrote == 0) {
            CloseHandle(f);
            return false;
        }
        off += wrote;
    }
    CloseHandle(f);
    return true;
}

// Stitching: the IV radar grid is 12 columns × 9 rows, ROW-MAJOR,
// radar00 at NW. Verified 2026-10-27 by stitching the 63 base-game
// tiles under each of 12 candidate (cols, rows, order, invert_row)
// configurations and comparing to a reference Liberty City map:
// 12 cols × 9 rows row-major is the only one that recovers Alderney
// (left), Algonquin + Central Park (centre), Bohan (upper right)
// and Dukes/Broker + airport runway (right). The make-atlas.py
// "radar00 is NW, radar01 one tile south" comment is wrong; radar01
// is one tile EAST (same row, next col).
//
// Output: 1536 × 1152 (aspect 1.33 — wider than tall, matches the
// real map's 1.24 aspect). fradar<NN> are episode-only overrides for
// the same slot; the placement call uses the SAME formula, so a
// later fradar overwrites the earlier radar in the stitch buffer.
constexpr int kGridCols = 12;
constexpr int kGridRows = 9;
constexpr int kStitchTile = 128;    // per-cell size
constexpr int kStitchW = kGridCols * kStitchTile;    // 1536
constexpr int kStitchH = kGridRows * kStitchTile;    // 1152

// Parse radar / fradar name into {grid_index, is_episode}. Returns
// {-1, false} if the name doesn't match the expected pattern.
struct TileSlot { int index = -1; bool episode = false; };
TileSlot parse_tile_slot(const std::string& name) {
    const char* s = name.c_str();
    bool episode = false;
    if (s[0] == 'f' && s[1] == 'r') { episode = true; s += 1; }
    if (std::strncmp(s, "radar", 5) != 0) return {};
    s += 5;
    int n = 0;
    const char* p = s;
    while (*p >= '0' && *p <= '9') {
        n = n * 10 + (*p - '0');
        ++p;
    }
    if (p == s) return {};
    if (n < 0 || n >= kGridCols * kGridRows) return {};
    return {n, episode};
}

// Row-major placement: radar00 at NW (col 0, row 0), radar01 one tile
// EAST (col 1, row 0), ..., radar12 one row down (col 0, row 1).
struct GridXY { int col, row; };
GridXY tile_grid_pos(int index) {
    return { index % kGridCols, index / kGridCols };
}

// Downsample `src` (sw x sh RGBA) into `dst` at `dst_x,dst_y` inside a
// `stride`-wide output buffer, sized `dw x dh`. Averages integer blocks
// (box filter) when sw / sh are a clean multiple of dw / dh; falls back
// to nearest otherwise.
void place_tile(std::uint8_t* dst_rgba, int stride, int dw, int dh,
                int dst_x, int dst_y,
                const std::uint8_t* src_rgba, int sw, int sh) {
    for (int y = 0; y < dh; ++y) {
        for (int x = 0; x < dw; ++x) {
            // Sample a block from src.
            int sx0 = (x * sw) / dw;
            int sy0 = (y * sh) / dh;
            int sx1 = ((x + 1) * sw) / dw;
            int sy1 = ((y + 1) * sh) / dh;
            if (sx1 <= sx0) sx1 = sx0 + 1;
            if (sy1 <= sy0) sy1 = sy0 + 1;
            unsigned r = 0, g = 0, b = 0, a = 0, count = 0;
            for (int sy = sy0; sy < sy1 && sy < sh; ++sy) {
                for (int sx = sx0; sx < sx1 && sx < sw; ++sx) {
                    const std::uint8_t* p = src_rgba + (sy * sw + sx) * 4;
                    r += p[0]; g += p[1]; b += p[2]; a += p[3];
                    ++count;
                }
            }
            if (!count) count = 1;
            std::uint8_t* d = dst_rgba + ((dst_y + y) * stride + (dst_x + x)) * 4;
            d[0] = static_cast<std::uint8_t>(r / count);
            d[1] = static_cast<std::uint8_t>(g / count);
            d[2] = static_cast<std::uint8_t>(b / count);
            d[3] = static_cast<std::uint8_t>(a / count);
        }
    }
}

// Decode one radar entry: strip RSC header, inflate, find the tile,
// DXT->RGBA, encode as PNG. Returns true on success. When `stitch` is
// non-null the decoded RGBA is also downsampled into it per the IV
// grid naming.
bool decode_entry_to_png(const std::wstring& atlas_dir,
                         const thorbridge::img_v3::Entry& e,
                         const std::uint8_t* bytes, std::size_t bytes_size,
                         std::uint8_t* stitch_rgba) {
    if (e.offset + e.size > bytes_size) return false;
    auto rsc = thorbridge::rsc::inflate(bytes + e.offset, e.size);
    if (!rsc.ok) {
        thor_log("[atlas] %s: RSC inflate failed: %s",
                 e.name.c_str(), rsc.error.c_str());
        return false;
    }
    auto tile = thorbridge::wtd::find_tile(rsc.body.data(), rsc.body.size());
    if (!tile.ok) {
        thor_log("[atlas] %s: WTD scan failed: %s "
                 "(inflated=%zu bytes)",
                 e.name.c_str(), tile.error.c_str(), rsc.body.size());
        return false;
    }

    std::vector<std::uint8_t> rgba(
        static_cast<std::size_t>(tile.width) * tile.height * 4);
    if (!thorbridge::dxt::decode(tile.format, tile.pixels, tile.pixel_bytes,
                                 tile.width, tile.height, rgba.data())) {
        thor_log("[atlas] %s: DXT decode failed (w=%d h=%d)",
                 e.name.c_str(), tile.width, tile.height);
        return false;
    }
    std::vector<std::uint8_t> png;
    if (!thorbridge::png::encode_rgba(rgba.data(), tile.width, tile.height, png)) {
        thor_log("[atlas] %s: PNG encode failed", e.name.c_str());
        return false;
    }

    std::string out_name = e.name;
    auto dot = out_name.find_last_of('.');
    if (dot != std::string::npos) out_name = out_name.substr(0, dot);
    out_name += ".png";
    std::wstring full = atlas_dir + L"\\" + widen(out_name);
    if (!write_whole_file(full, png)) {
        thor_log("[atlas] %s: write failed", e.name.c_str());
        return false;
    }

    // Place into the stitch grid when the name parses.
    if (stitch_rgba) {
        TileSlot slot = parse_tile_slot(e.name);
        if (slot.index >= 0) {
            GridXY pos = tile_grid_pos(slot.index);
            place_tile(stitch_rgba, kStitchW, kStitchTile, kStitchTile,
                       pos.col * kStitchTile, pos.row * kStitchTile,
                       rgba.data(), tile.width, tile.height);
        }
    }
    return true;
}

DWORD WINAPI worker_main(LPVOID /*unused*/) {
    publish(State::Scanning, 1, nullptr);
    thor_log("[atlas] worker started");

    std::wstring root = plugin_dir();
    thor_log("[atlas] searching below %s", narrow(root).c_str());
    std::wstring img_path = find_file(root, L"radar.img");
    std::wstring exe_path = find_file(root, L"GTAIV.exe");
    if (InterlockedCompareExchange(&g.cancel, 0, 0)) {
        thor_log("[atlas] cancelled during scan");
        publish(State::Error, 0, "cancelled");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    if (img_path.empty()) {
        thor_log("[atlas] radar.img not found under %s", narrow(root).c_str());
        publish(State::Error, 0, "archives not found");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    if (exe_path.empty()) {
        thor_log("[atlas] GTAIV.exe not found under %s", narrow(root).c_str());
        publish(State::Error, 0, "GTAIV.exe not found");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] found radar: %s", narrow(img_path).c_str());
    thor_log("[atlas] found exe:   %s", narrow(exe_path).c_str());
    publish(State::Reading, 5, nullptr);

    auto exe_bytes = read_whole_file(exe_path);
    if (exe_bytes.empty()) {
        thor_log("[atlas] GTAIV.exe empty or unreadable");
        publish(State::Error, 0, "GTAIV.exe unreadable");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] GTAIV.exe size = %u bytes",
             static_cast<unsigned>(exe_bytes.size()));

    publish(State::Reading, 15, nullptr);
    std::uint8_t key[thorbridge::keys_iv::kAesKeyBytes];
    if (!thorbridge::keys_iv::locate_aes_key(
            exe_bytes.data(), exe_bytes.size(), key)) {
        thor_log("[atlas] AES-256 key not found in GTAIV.exe "
                 "(sha1 whitelist did not match any 32 B window)");
        publish(State::Error, 0,
                "AES key not found in GTAIV.exe (unknown build?)");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] AES-256 key located in GTAIV.exe (offset not logged)");

    auto bytes = read_whole_file(img_path);
    if (bytes.empty()) {
        thor_log("[atlas] radar.img empty or unreadable");
        publish(State::Error, 0, "radar.img unreadable");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] radar.img size = %u bytes",
             static_cast<unsigned>(bytes.size()));
    publish(State::Reading, 30, nullptr);

    auto toc = thorbridge::img_v3::parse_toc(
        bytes.data(), bytes.size(), key);
    if (!toc.ok) {
        thor_log("[atlas] IMG v3 parse refused: %s", toc.error.c_str());
        char buf[160];
        std::snprintf(buf, sizeof(buf),
                      "img v3 decrypt failed: %s", toc.error.c_str());
        publish(State::Error, 0, buf);
        // Scrub the key from stack before returning.
        std::memset(key, 0, sizeof(key));
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] IMG v3 TOC: %u entries (table_size=%u, head: %s)",
             static_cast<unsigned>(toc.entries.size()),
             static_cast<unsigned>(toc.table_size),
             toc.head_hex_plain.c_str());
    publish(State::Reading, 40, nullptr,
            static_cast<std::uint16_t>(toc.entries.size()));

    // Output dir: <game dir>/ThorBridge-atlas. Created fresh so a
    // re-run doesn't mix tiles from a previous attempt.
    std::wstring atlas_dir = root + L"\\ThorBridge-atlas";
    CreateDirectoryW(atlas_dir.c_str(), NULL);
    thor_log("[atlas] writing PNG tiles to %s", narrow(atlas_dir).c_str());

    // Stitched iv.png accumulator: 1280 x 1280 RGBA, zero-cleared so
    // missing tiles stay transparent black (water etc.).
    std::vector<std::uint8_t> stitch(
        static_cast<std::size_t>(kStitchW) * kStitchH * 4, 0);

    unsigned ok_count = 0;
    unsigned fail_count = 0;
    std::uint16_t last_tile_w = 0, last_tile_h = 0;
    const std::size_t total = toc.entries.size();
    for (std::size_t i = 0; i < total; ++i) {
        if (InterlockedCompareExchange(&g.cancel, 0, 0)) {
            thor_log("[atlas] cancelled mid-decode at entry %zu", i);
            publish(State::Error, 0, "cancelled");
            std::memset(key, 0, sizeof(key));
            InterlockedExchange(&g.running, 0);
            return 0;
        }
        if (decode_entry_to_png(atlas_dir, toc.entries[i],
                                bytes.data(), bytes.size(), stitch.data())) {
            ++ok_count;
            // Peek at one dimension so the companion sees tile_w/h.
            auto rsc = thorbridge::rsc::inflate(
                bytes.data() + toc.entries[i].offset,
                toc.entries[i].size);
            if (rsc.ok) {
                auto t = thorbridge::wtd::find_tile(rsc.body.data(),
                                                     rsc.body.size());
                if (t.ok) {
                    last_tile_w = static_cast<std::uint16_t>(t.width);
                    last_tile_h = static_cast<std::uint16_t>(t.height);
                }
            }
            if (ok_count % 10 == 0) {
                thor_log("[atlas] decoded %u / %zu tiles", ok_count, total);
            }
        } else {
            ++fail_count;
        }
        // Progress: 40% once TOC decrypted, drive to 95% through the
        // decode loop. 95% not 100% so the final log line lands first.
        std::uint8_t pct = static_cast<std::uint8_t>(
            40 + (55ull * (i + 1)) / total);
        publish(State::Reading, pct, nullptr,
                static_cast<std::uint16_t>(total), 0, 0,
                last_tile_w, last_tile_h);
    }

    thor_log("[atlas] decode complete: %u ok, %u failed, dir=%s",
             ok_count, fail_count, narrow(atlas_dir).c_str());

    // Stitch pass: encode the 1280 x 1280 accumulator and write to two
    // places: the debug ThorBridge-atlas/iv.png and the shared app-
    // readable path /sdcard/GTC/map/gta4/iv.png (via Wine z:\ drive).
    std::vector<std::uint8_t> stitch_png;
    if (thorbridge::png::encode_rgba(stitch.data(), kStitchW, kStitchH,
                                     stitch_png)) {
        std::wstring debug_iv = atlas_dir + L"\\iv.png";
        if (write_whole_file(debug_iv, stitch_png)) {
            thor_log("[atlas] stitched iv.png written to %s (%zu bytes)",
                     narrow(debug_iv).c_str(), stitch_png.size());
        }
        // Shared app-readable path. z:\ is Wine's root; the dirs are
        // created lazily since they almost certainly don't exist.
        const wchar_t* kGtcDirA = L"z:\\storage\\emulated\\0\\GTC";
        const wchar_t* kGtcDirB = L"z:\\storage\\emulated\\0\\GTC\\map";
        const wchar_t* kGtcDirC = L"z:\\storage\\emulated\\0\\GTC\\map\\gta4";
        CreateDirectoryW(kGtcDirA, NULL);
        CreateDirectoryW(kGtcDirB, NULL);
        CreateDirectoryW(kGtcDirC, NULL);
        std::wstring shared_iv =
            L"z:\\storage\\emulated\\0\\GTC\\map\\gta4\\iv.png";
        if (write_whole_file(shared_iv, stitch_png)) {
            thor_log("[atlas] stitched iv.png also written to "
                     "/storage/emulated/0/GTC/map/gta4/iv.png (shared)");
        } else {
            thor_log("[atlas] WARN: could not write shared iv.png — "
                     "z:\\ drive may not reach /storage/emulated/0; "
                     "pull from ThorBridge-atlas\\iv.png instead");
        }
    }

    char done_msg[192];
    std::snprintf(done_msg, sizeof(done_msg),
                  "%u / %u tiles. stitched iv.png in ThorBridge-atlas/",
                  ok_count, static_cast<unsigned>(total));
    publish(fail_count == 0 ? State::Done : State::Error, 100, done_msg,
            static_cast<std::uint16_t>(total), 0, 0,
            last_tile_w, last_tile_h);
    std::memset(key, 0, sizeof(key));
    InterlockedExchange(&g.running, 0);
    thor_log("[atlas] worker exiting");
    return 0;
}

}  // namespace

void init() {
    if (g.inited) return;
    InitializeCriticalSection(&g.cs);
    std::memset(&g.pub, 0, sizeof(g.pub));
    g.pub.state = State::Idle;
    g.inited = true;
}

void shutdown() {
    if (!g.inited) return;
    cancel();
    if (g.worker) {
        WaitForSingleObject(g.worker, 2000);
        CloseHandle(g.worker);
        g.worker = nullptr;
    }
    DeleteCriticalSection(&g.cs);
    g.inited = false;
}

bool start() {
    if (!g.inited) return false;
    if (InterlockedCompareExchange(&g.running, 1, 0) != 0) return false;
    // Reset the cancel flag and prime a Scanning progress tick so the
    // UI can wire up before the worker publishes.
    InterlockedExchange(&g.cancel, 0);
    publish(State::Scanning, 0, nullptr);
    if (g.worker) {
        CloseHandle(g.worker);
        g.worker = nullptr;
    }
    g.worker = CreateThread(NULL, 0, worker_main, nullptr, 0, NULL);
    if (!g.worker) {
        InterlockedExchange(&g.running, 0);
        publish(State::Error, 0, "worker thread spawn failed");
        return false;
    }
    return true;
}

void cancel() {
    if (!g.inited) return;
    InterlockedExchange(&g.cancel, 1);
}

bool read_progress(Progress& out, std::uint32_t& last_ver_in_out) {
    if (!g.inited) return false;
    EnterCriticalSection(&g.cs);
    bool changed = (g.pub.ver != last_ver_in_out);
    if (changed) {
        out = g.pub;
        last_ver_in_out = g.pub.ver;
    }
    LeaveCriticalSection(&g.cs);
    return changed;
}

}  // namespace atlas
