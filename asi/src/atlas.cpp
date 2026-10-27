#include "atlas.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "img.h"
#include "img_v3.h"
#include "keys_iv.h"
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

    // Log every entry's name + size so the next-pass WTD decoder knows
    // exactly what the archive looks like.
    for (std::size_t i = 0; i < toc.entries.size(); ++i) {
        const auto& e = toc.entries[i];
        char head[128] = {0};
        std::size_t take = e.size < 32 ? e.size : 32;
        std::size_t w = 0;
        for (std::size_t j = 0; j < take && w + 3 < sizeof(head); ++j) {
            std::snprintf(head + w, sizeof(head) - w, "%02X ",
                          bytes[e.offset + j]);
            w += 3;
        }
        thor_log("[atlas] entry[%zu] name='%s' size=%u rsc=%d type=%u head: %s",
                 i, e.name.c_str(), static_cast<unsigned>(e.size),
                 e.is_rsc ? 1 : 0,
                 static_cast<unsigned>(e.resource_type), head);
    }

    // WTD / RSC / DXT decode lands next session with the first entry
    // heads above in hand. For now, publish a clean "decrypted ok,
    // decode pending" result the companion can show.
    char ok_msg[160];
    std::snprintf(ok_msg, sizeof(ok_msg),
                  "TOC decrypted: %u entries. wtd decode pending.",
                  static_cast<unsigned>(toc.entries.size()));
    publish(State::Error, 0, ok_msg);
    std::memset(key, 0, sizeof(key));
    InterlockedExchange(&g.running, 0);
    thor_log("[atlas] worker exiting (TOC-only pass)");
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
