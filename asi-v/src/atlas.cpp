#include "atlas.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "log.h"

#include "../../common/keys_v.h"
#include "../../common/rpf7.h"
#include "../../common/rsc7.h"
#include "../../common/ytd.h"

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

void publish(State st, std::uint8_t pct, const char* err) {
    EnterCriticalSection(&g.cs);
    ++g.pub.ver;
    g.pub.state = st;
    g.pub.pct = pct;
    if (err) {
        std::snprintf(g.pub.err, sizeof(g.pub.err), "%s", err);
    } else {
        g.pub.err[0] = '\0';
    }
    LeaveCriticalSection(&g.cs);
}

// Returns the directory the ASI itself lives in (the game folder for
// Script Hook V setups, same as the IV atlas's convention).
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

std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(),
                                NULL, 0, NULL, NULL);
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(),
                        s.data(), n, NULL, NULL);
    return s;
}

// Depth-first search for a filename under `root`. Stops at the first
// match. Case-insensitive.
std::wstring find_file(const std::wstring& root, const wchar_t* name,
                       int depth = 0) {
    if (depth > 8) return L"";
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

// Collect all .rpf files under `root`. Depth-capped to protect against
// symlink loops. The retail install is thousands of files but under a
// hundred .rpf archives, so the result stays small.
void collect_rpfs(const std::wstring& root,
                  std::vector<std::wstring>& out, int depth = 0) {
    if (depth > 8) return;
    WIN32_FIND_DATAW fd;
    std::wstring probe = root + L"\\*";
    HANDLE h = FindFirstFileW(probe.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        if (fd.cFileName[0] == L'.' &&
            (fd.cFileName[1] == 0 ||
             (fd.cFileName[1] == L'.' && fd.cFileName[2] == 0))) continue;
        std::wstring full = root + L"\\" + fd.cFileName;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            collect_rpfs(full, out, depth + 1);
            continue;
        }
        // Match *.rpf case-insensitively.
        std::size_t len = wcslen(fd.cFileName);
        if (len > 4) {
            const wchar_t* ext = fd.cFileName + len - 4;
            if (_wcsicmp(ext, L".rpf") == 0) {
                out.push_back(full);
            }
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
}

std::vector<std::uint8_t> read_whole_file(const std::wstring& path,
                                          std::size_t max_bytes) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return {};
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(f, &sz)) { CloseHandle(f); return {}; }
    if (sz.QuadPart <= 0 ||
        static_cast<std::uint64_t>(sz.QuadPart) > max_bytes) {
        CloseHandle(f);
        return {};
    }
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(sz.QuadPart));
    DWORD got = 0;
    std::size_t off = 0;
    while (off < buf.size()) {
        DWORD want = static_cast<DWORD>(buf.size() - off);
        if (!ReadFile(f, buf.data() + off, want, &got, NULL) || got == 0) break;
        off += got;
    }
    CloseHandle(f);
    buf.resize(off);
    return buf;
}

const char* kind_label(thorbridge::rpf7::EntryKind k) {
    using thorbridge::rpf7::EntryKind;
    switch (k) {
        case EntryKind::Directory:    return "dir";
        case EntryKind::BinaryFile:   return "bin";
        case EntryKind::ResourceFile: return "res";
    }
    return "???";
}

const char* enc_label(thorbridge::rpf7::Encryption e) {
    using thorbridge::rpf7::Encryption;
    switch (e) {
        case Encryption::None: return "none";
        case Encryption::Open: return "open";
        case Encryption::Aes:  return "aes";
        case Encryption::Ng:   return "ng";
    }
    return "???";
}

// Log the first N entries of an archive. For the top-level pass we
// cap the per-archive dump so a game dir with 50 archives doesn't
// bury the interesting bits in a 50 MB log file.
void log_archive_entries(const char* label,
                         const thorbridge::rpf7::Archive& a,
                         std::size_t max_entries = 24) {
    thor_log("[atlas] %s: enc=%s entries=%u names_bytes=%u",
             label, enc_label(a.encryption),
             static_cast<unsigned>(a.entry_count),
             static_cast<unsigned>(a.names_length));
    std::size_t limit = a.entries.size() < max_entries
        ? a.entries.size()
        : max_entries;
    for (std::size_t i = 0; i < limit; ++i) {
        const auto& e = a.entries[i];
        thor_log("[atlas]   [%llu] %s %s size=%u off=%llu",
                 static_cast<unsigned long long>(i),
                 kind_label(e.kind), e.name.c_str(),
                 static_cast<unsigned>(e.file_size_bytes),
                 static_cast<unsigned long long>(e.file_offset_bytes));
    }
    if (a.entries.size() > limit) {
        thor_log("[atlas]   ... + %llu more entries",
                 static_cast<unsigned long long>(a.entries.size() - limit));
    }
}

// Called when we locate scaleform_minimap.rpf. Opens it, logs its own
// entries, and for any ytd inside: extract + rsc7::open + ytd::open,
// then log the texture roster. This is the information the real
// decoder commit will encode against.
void probe_minimap_archive(const thorbridge::rpf7::Archive& parent,
                           const thorbridge::rpf7::Entry& entry,
                           const thorbridge::keys_v::ExtractedKeys* keys) {
    thor_log("[atlas] descending into %s (%u bytes)",
             entry.name.c_str(),
             static_cast<unsigned>(entry.file_size_bytes));
    std::vector<std::uint8_t> inner_bytes;
    if (!thorbridge::rpf7::extract(parent, entry, keys, inner_bytes)) {
        thor_log("[atlas] FAILED to extract %s from parent archive",
                 entry.name.c_str());
        return;
    }
    thorbridge::rpf7::Archive inner;
    if (!thorbridge::rpf7::open(inner_bytes.data(), inner_bytes.size(),
                                 entry.name.c_str(),
                                 static_cast<std::uint32_t>(inner_bytes.size()),
                                 keys, inner)) {
        thor_log("[atlas] FAILED to parse nested RPF7 for %s",
                 entry.name.c_str());
        return;
    }
    log_archive_entries(entry.name.c_str(), inner, /*max_entries=*/64);

    // Dig into every YTD entry in the nested archive. For each one,
    // log the ytd metadata so we know whether the retail install packs
    // all tiles into one dictionary or splits them.
    for (const auto& res : inner.entries) {
        if (res.kind != thorbridge::rpf7::EntryKind::ResourceFile) continue;
        std::string lower = res.name;
        for (auto& c : lower) {
            if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        }
        if (lower.size() < 4 ||
            lower.compare(lower.size() - 4, 4, ".ytd") != 0) {
            continue;
        }
        thor_log("[atlas] opening ytd '%s' (%u bytes)",
                 res.name.c_str(),
                 static_cast<unsigned>(res.file_size_bytes));
        std::vector<std::uint8_t> ytd_bytes;
        if (!thorbridge::rpf7::extract(inner, res, keys, ytd_bytes)) {
            thor_log("[atlas]   extract failed");
            continue;
        }
        thorbridge::rsc7::Header rsc_h{};
        std::vector<std::uint8_t> payload;
        if (!thorbridge::rsc7::open(ytd_bytes.data(), ytd_bytes.size(),
                                     rsc_h, payload)) {
            thor_log("[atlas]   RSC7 parse failed (first 8 bytes: "
                     "%02x %02x %02x %02x %02x %02x %02x %02x)",
                     ytd_bytes.size() > 0 ? ytd_bytes[0] : 0,
                     ytd_bytes.size() > 1 ? ytd_bytes[1] : 0,
                     ytd_bytes.size() > 2 ? ytd_bytes[2] : 0,
                     ytd_bytes.size() > 3 ? ytd_bytes[3] : 0,
                     ytd_bytes.size() > 4 ? ytd_bytes[4] : 0,
                     ytd_bytes.size() > 5 ? ytd_bytes[5] : 0,
                     ytd_bytes.size() > 6 ? ytd_bytes[6] : 0,
                     ytd_bytes.size() > 7 ? ytd_bytes[7] : 0);
            continue;
        }
        thor_log("[atlas]   RSC7 v=%u sys_sz=%u gfx_sz=%u sys_t=%u gfx_t=%u",
                 static_cast<unsigned>(rsc_h.version),
                 static_cast<unsigned>(rsc_h.system_size),
                 static_cast<unsigned>(rsc_h.graphics_size),
                 static_cast<unsigned>(rsc_h.system_type),
                 static_cast<unsigned>(rsc_h.graphics_type));
        thorbridge::ytd::Dictionary dict;
        if (!thorbridge::ytd::open(payload.data(), rsc_h.system_size,
                                    rsc_h.graphics_size, dict)) {
            thor_log("[atlas]   ytd parse failed on payload %llu bytes",
                     static_cast<unsigned long long>(payload.size()));
            continue;
        }
        thor_log("[atlas]   ytd has %llu textures",
                 static_cast<unsigned long long>(dict.textures.size()));
        for (const auto& t : dict.textures) {
            thor_log("[atlas]     tex '%s' %ux%u fmt=0x%08X levels=%u "
                     "stride=%u pixels=%u",
                     t.name.c_str(), t.width, t.height,
                     static_cast<unsigned>(t.format),
                     static_cast<unsigned>(t.levels),
                     static_cast<unsigned>(t.stride),
                     static_cast<unsigned>(t.full_data_size_bytes));
        }
    }
}

DWORD WINAPI worker_main(LPVOID /*unused*/) {
    publish(State::Scanning, 1, nullptr);
    thor_log("[atlas] probe worker started (asi-v)");

    std::wstring root = plugin_dir();
    thor_log("[atlas] game dir = %s", narrow(root).c_str());

    // --- Step 1: locate GTA5.exe and run keys_v::locate_all ---------
    std::wstring exe_path = find_file(root, L"GTA5.exe");
    if (exe_path.empty()) {
        // Try the Enhanced-edition filename so we can report Enhanced
        // specifically. First-pass refusal; the real support decision
        // lands later.
        exe_path = find_file(root, L"GTA5_Enhanced.exe");
        if (!exe_path.empty()) {
            thor_log("[atlas] GTA5_Enhanced.exe found at %s",
                     narrow(exe_path).c_str());
            publish(State::Error, 0, "not supported on Enhanced");
            InterlockedExchange(&g.running, 0);
            return 0;
        }
        thor_log("[atlas] GTA5.exe not found under %s",
                 narrow(root).c_str());
        publish(State::Error, 0, "GTA5.exe not found");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] GTA5.exe = %s", narrow(exe_path).c_str());

    if (InterlockedCompareExchange(&g.cancel, 0, 0)) {
        publish(State::Error, 0, "cancelled");
        InterlockedExchange(&g.running, 0);
        return 0;
    }

    // Cap at 500 MB in case someone accidentally points at the wrong
    // file. Retail GTA5.exe is around 80 MB.
    auto exe_bytes = read_whole_file(exe_path, 500ull * 1024 * 1024);
    if (exe_bytes.empty()) {
        thor_log("[atlas] GTA5.exe unreadable or empty");
        publish(State::Error, 0, "GTA5.exe unreadable");
        InterlockedExchange(&g.running, 0);
        return 0;
    }
    thor_log("[atlas] GTA5.exe size = %llu bytes",
             static_cast<unsigned long long>(exe_bytes.size()));
    publish(State::Reading, 10, nullptr);

    // locate_all is heavy (four full passes over the exe). Pin it to
    // the heap so a tight ASI stack doesn't push the thread off.
    auto keys = std::make_unique<thorbridge::keys_v::ExtractedKeys>();
    bool keys_ok = thorbridge::keys_v::locate_all(
        exe_bytes.data(), exe_bytes.size(), *keys);
    if (keys_ok) {
        thor_log("[atlas] keys_v: all 375 blobs located "
                 "(aes + 101 ng keys + 272 ng tables + lut)");
    } else {
        thor_log("[atlas] keys_v: NOT all blobs located; "
                 "AES / NG archives will be refused this run");
    }
    publish(State::Reading, 30, nullptr);

    if (InterlockedCompareExchange(&g.cancel, 0, 0)) {
        std::memset(keys.get(), 0, sizeof(*keys));
        publish(State::Error, 0, "cancelled");
        InterlockedExchange(&g.running, 0);
        return 0;
    }

    // --- Step 2: enumerate all .rpf files under the game dir ------
    std::vector<std::wstring> rpfs;
    collect_rpfs(root, rpfs);
    thor_log("[atlas] %llu .rpf archives found under game dir",
             static_cast<unsigned long long>(rpfs.size()));
    publish(State::Reading, 40, nullptr);

    // Priority order: things most likely to hold scaleform_minimap.rpf
    // move to the front. Everything else we still log so we have the
    // full picture.
    auto priority = [](const std::wstring& p) -> int {
        auto find_in = [&](const wchar_t* needle) {
            return p.find(needle) != std::wstring::npos;
        };
        if (find_in(L"x64h.rpf"))        return 0;
        if (find_in(L"update.rpf"))      return 1;
        if (find_in(L"x64c.rpf"))        return 2;
        if (find_in(L"x64g.rpf"))        return 3;
        if (find_in(L"common.rpf"))      return 4;
        return 99;
    };
    std::sort(rpfs.begin(), rpfs.end(),
              [&](const std::wstring& a, const std::wstring& b) {
                  return priority(a) < priority(b);
              });
    for (const auto& p : rpfs) {
        thor_log("[atlas]   rpf: %s", narrow(p).c_str());
    }

    // --- Step 3: open each archive, hunt for scaleform_minimap.rpf -
    constexpr std::size_t kMaxArchiveBytes = 2ull * 1024 * 1024 * 1024;
    std::size_t touched = 0;
    for (const auto& path : rpfs) {
        if (InterlockedCompareExchange(&g.cancel, 0, 0)) {
            std::memset(keys.get(), 0, sizeof(*keys));
            publish(State::Error, 0, "cancelled");
            InterlockedExchange(&g.running, 0);
            return 0;
        }
        // Hard-cap the number of archives we actually open to avoid a
        // multi-minute probe pass; the sorted priority list means the
        // most useful ones are inspected first.
        if (touched >= 12) break;
        auto bytes = read_whole_file(path, kMaxArchiveBytes);
        if (bytes.empty()) {
            thor_log("[atlas] skip %s (unreadable or too large)",
                     narrow(path).c_str());
            continue;
        }
        thorbridge::rpf7::Archive a;
        std::string label = narrow(path);
        // Strip to just the filename for the NG derivation; the
        // retail game only ever sees the leaf name.
        std::size_t last = label.find_last_of("/\\");
        std::string leaf = last == std::string::npos ? label : label.substr(last + 1);
        if (!thorbridge::rpf7::open(bytes.data(), bytes.size(),
                                     leaf.c_str(),
                                     static_cast<std::uint32_t>(bytes.size()),
                                     keys_ok ? keys.get() : nullptr, a)) {
            thor_log("[atlas] rpf7::open refused %s", leaf.c_str());
            continue;
        }
        log_archive_entries(leaf.c_str(), a);
        ++touched;

        // Look for scaleform_minimap.rpf anywhere in this archive.
        for (const auto& e : a.entries) {
            if (e.kind != thorbridge::rpf7::EntryKind::BinaryFile) continue;
            std::string lower = e.name;
            for (auto& c : lower) {
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            }
            if (lower == "scaleform_minimap.rpf") {
                thor_log("[atlas] HIT: scaleform_minimap.rpf under %s",
                         leaf.c_str());
                probe_minimap_archive(a, e, keys_ok ? keys.get() : nullptr);
            }
        }
    }

    // Scrub the extracted key material before we return control.
    std::memset(keys.get(), 0, sizeof(*keys));

    thor_log("[atlas] probe worker exiting (touched %llu / %llu archives)",
             static_cast<unsigned long long>(touched),
             static_cast<unsigned long long>(rpfs.size()));
    publish(State::Error, 100,
            "probe complete; see ThorBridgeV.log for details");
    InterlockedExchange(&g.running, 0);
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
