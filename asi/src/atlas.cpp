#include "atlas.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "img.h"
#include "log.h"

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

// Depth-first search for radar.img under `root`. Stops at the first
// match. The common install layout is <game>/pc/textures/radar.img so
// in practice the search is shallow.
std::wstring find_radar(const std::wstring& root, int depth = 0) {
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
            found = find_radar(full, depth + 1);
            if (!found.empty()) break;
        } else {
            // Case-insensitive compare on the name.
            if (_wcsicmp(fd.cFileName, L"radar.img") == 0) {
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

DWORD WINAPI worker_main(LPVOID /*unused*/) {
    publish(State::Scanning, 1, nullptr);
    thor_log("[atlas] worker started");

    std::wstring root = plugin_dir();
    thor_log("[atlas] searching below %s", narrow(root).c_str());
    std::wstring img_path = find_radar(root);
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
    thor_log("[atlas] found %s", narrow(img_path).c_str());
    publish(State::Reading, 10, nullptr);

    auto bytes = read_whole_file(img_path);
    if (bytes.empty()) {
        thor_log("[atlas] radar.img empty or unreadable");
        publish(State::Error, 0, "radar.img unreadable");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] radar.img size = %u bytes",
             static_cast<unsigned>(bytes.size()));

    auto toc = thorbridge::img::parse_toc(bytes.data(), bytes.size());
    thor_log("[atlas] radar.img head bytes: %s", toc.head_hex.c_str());
    if (!toc.ok) {
        thor_log("[atlas] IMG parse refused: %s", toc.error.c_str());
        char buf[160];
        std::snprintf(buf, sizeof(buf),
                      "img format pending (head: %s)",
                      toc.head_hex.c_str());
        publish(State::Error, 0, buf);
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] IMG VER2 TOC: %u entries",
             static_cast<unsigned>(toc.entries.size()));

    // Log the first few entries by name + a hex head of their body so
    // Gate IV-1 can show us the real WTD / RSC header. The real WTD
    // decoder lands in the next pass once that header is known.
    std::size_t probe_n = toc.entries.size() < 3 ? toc.entries.size() : 3;
    for (std::size_t i = 0; i < probe_n; ++i) {
        const auto& e = toc.entries[i];
        char head[128] = {0};
        std::size_t take = e.size < 32 ? e.size : 32;
        std::size_t w = 0;
        for (std::size_t j = 0; j < take && w + 3 < sizeof(head); ++j) {
            std::snprintf(head + w, sizeof(head) - w, "%02X ",
                          bytes[e.offset + j]);
            w += 3;
        }
        thor_log("[atlas] entry[%zu] name='%s' size=%u head: %s",
                 i, e.name.c_str(), static_cast<unsigned>(e.size), head);
    }

    // Diagnostic-only pass for this session: the archive was found and
    // read, but the WTD -> DXT -> PNG pipeline needs real bytes from
    // Gate IV-1 to be sure of the resource header shape. Report done
    // with a clear note so the companion shows a specific message.
    publish(State::Error, 0,
            "wtd decode pending: see ThorBridge.log for entry heads");
    InterlockedExchange(&g.running, 0);
    thor_log("[atlas] worker exiting (diagnostic pass)");
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
