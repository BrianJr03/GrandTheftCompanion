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

// Read up to `max_bytes` from the start of a file. If the file is
// shorter than max_bytes, returns the whole thing; if longer, returns
// exactly max_bytes. Used for header+TOC+names reads on the top-level
// RPF archives, which can be multi-GB on retail and must never be
// slurped whole on a mobile device.
std::vector<std::uint8_t> read_file_prefix(const std::wstring& path,
                                           std::size_t max_bytes) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return {};
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(f, &sz)) { CloseHandle(f); return {}; }
    if (sz.QuadPart <= 0) { CloseHandle(f); return {}; }
    std::size_t want_total =
        static_cast<std::uint64_t>(sz.QuadPart) < max_bytes
            ? static_cast<std::size_t>(sz.QuadPart)
            : max_bytes;
    std::vector<std::uint8_t> buf(want_total);
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

// Read exactly `size` bytes from `path` starting at byte `offset`.
// Used to pull a specific RPF entry out of a huge archive without
// loading the archive whole. Returns an empty vector on failure.
std::vector<std::uint8_t> read_file_range(const std::wstring& path,
                                          std::uint64_t offset,
                                          std::uint32_t size) {
    if (size == 0) return {};
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return {};
    LARGE_INTEGER off_li;
    off_li.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(f, off_li, nullptr, FILE_BEGIN)) {
        CloseHandle(f);
        return {};
    }
    std::vector<std::uint8_t> buf(size);
    DWORD got = 0;
    std::size_t pos = 0;
    while (pos < size) {
        DWORD want = static_cast<DWORD>(size - pos);
        if (!ReadFile(f, buf.data() + pos, want, &got, NULL) || got == 0) break;
        pos += got;
    }
    CloseHandle(f);
    buf.resize(pos);
    return buf;
}

// Full file size in bytes, or 0 on error. Used to pass the correct
// archive length into rpf7::open for NG derivation even when we only
// loaded a prefix of the file.
std::uint64_t file_size_bytes(const std::wstring& path) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return 0;
    LARGE_INTEGER sz;
    BOOL ok = GetFileSizeEx(f, &sz);
    CloseHandle(f);
    if (!ok || sz.QuadPart <= 0) return 0;
    return static_cast<std::uint64_t>(sz.QuadPart);
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

// Called when we locate scaleform_minimap.rpf. Opens it, logs its
// own entries, and for any ytd inside: extract + rsc7::open +
// ytd::open, then log the texture roster. This is the information
// the real decoder commit will encode against.
//
// Takes the parent archive's FILE PATH instead of the parent Archive
// struct so we can byte-range read exactly the nested archive's
// bytes without having loaded the whole multi-GB parent into memory.
void probe_minimap_archive(const std::wstring& parent_path,
                           const thorbridge::rpf7::Entry& entry,
                           const thorbridge::keys_v::ExtractedKeys* keys) {
    thor_log("[atlas] descending into %s (%u bytes @ off=%llu)",
             entry.name.c_str(),
             static_cast<unsigned>(entry.file_size_bytes),
             static_cast<unsigned long long>(entry.file_offset_bytes));
    auto inner_bytes = read_file_range(parent_path, entry.file_offset_bytes,
                                        entry.file_size_bytes);
    if (inner_bytes.size() != entry.file_size_bytes) {
        thor_log("[atlas] FAILED to range-read %s (got %llu / %u bytes)",
                 entry.name.c_str(),
                 static_cast<unsigned long long>(inner_bytes.size()),
                 static_cast<unsigned>(entry.file_size_bytes));
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

    auto keys = std::make_unique<thorbridge::keys_v::ExtractedKeys>();
    bool keys_ok = false;

    // Side-car path: if a Keys/ folder exists next to GTA5.exe with
    // the four CodeWalker-format files, verify + load from there and
    // skip the exe scan entirely. Needed for installs whose exe has
    // been stripped / repacked so the embedded SHA-1 fingerprints no
    // longer match any 32 B / 272 B / 1 KB / 256 B window (common on
    // mobile Wine distributions).
    //
    // Expected layout:
    //   <game dir>\Keys\gtav_aes_key.dat        (32 bytes)
    //   <game dir>\Keys\gtav_ng_key.dat         (27,472 bytes)
    //   <game dir>\Keys\gtav_ng_decrypt_tables.dat (278,528 bytes)
    //   <game dir>\Keys\gtav_hash_lut.dat       (256 bytes)
    //
    // User dumps these via CodeWalker on a PC with a working install.
    std::wstring keys_dir = root + L"\\Keys";
    auto side_aes    = read_whole_file(keys_dir + L"\\gtav_aes_key.dat",
                                       64);
    auto side_ngk    = read_whole_file(keys_dir + L"\\gtav_ng_key.dat",
                                       64 * 1024);
    auto side_ngt    = read_whole_file(keys_dir + L"\\gtav_ng_decrypt_tables.dat",
                                       512 * 1024);
    auto side_lut    = read_whole_file(keys_dir + L"\\gtav_hash_lut.dat",
                                       512);
    if (!side_aes.empty() && !side_ngk.empty() &&
        !side_ngt.empty() && !side_lut.empty()) {
        thor_log("[atlas] Keys/ folder found; trying side-car load "
                 "(aes=%llu ng_keys=%llu ng_tables=%llu lut=%llu bytes)",
                 static_cast<unsigned long long>(side_aes.size()),
                 static_cast<unsigned long long>(side_ngk.size()),
                 static_cast<unsigned long long>(side_ngt.size()),
                 static_cast<unsigned long long>(side_lut.size()));
        if (thorbridge::keys_v::verify_and_load(
                side_aes.data(), side_aes.size(),
                side_ngk.data(), side_ngk.size(),
                side_ngt.data(), side_ngt.size(),
                side_lut.data(), side_lut.size(),
                *keys)) {
            keys_ok = true;
            thor_log("[atlas] side-car keys verified against published "
                     "SHA-1 fingerprints; skipping exe scan");
            // Scrub the heap copies now that keys are in `keys`.
            std::memset(side_aes.data(), 0, side_aes.size());
            std::memset(side_ngk.data(), 0, side_ngk.size());
            std::memset(side_ngt.data(), 0, side_ngt.size());
            std::memset(side_lut.data(), 0, side_lut.size());
            publish(State::Reading, 30, nullptr);
        } else {
            thor_log("[atlas] side-car verification FAILED (sizes / SHA-1 "
                     "mismatch); falling back to exe scan");
        }
    }

    if (!keys_ok) {
        // Cap at 500 MB in case someone accidentally points at the
        // wrong file. Retail GTA5.exe is around 80 MB.
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

        keys_ok = thorbridge::keys_v::locate_all(
            exe_bytes.data(), exe_bytes.size(), *keys);
        if (keys_ok) {
            thor_log("[atlas] keys_v: all 375 blobs located "
                     "(aes + 101 ng keys + 272 ng tables + lut)");
        } else {
            thor_log("[atlas] keys_v: NOT all blobs located; "
                     "AES / NG archives will be refused this run. "
                     "Drop a CodeWalker Keys/ folder next to GTA5.exe "
                     "to bypass the exe scan.");
        }
        publish(State::Reading, 30, nullptr);
    }

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
    // Prefix-only reads so a multi-GB archive (x64h.rpf is 1.7 GB,
    // x64g.rpf is 2.5 GB on retail) does not blow Wine's heap on the
    // Thor. 32 MB is more than enough for header + TOC + names on
    // every archive we have seen, including the biggest ones.
    constexpr std::size_t kTocPrefixBytes = 32ull * 1024 * 1024;
    constexpr std::size_t kMaxArchivesToTry = 16;
    std::size_t attempted = 0;
    std::size_t touched = 0;
    for (const auto& path : rpfs) {
        if (InterlockedCompareExchange(&g.cancel, 0, 0)) {
            std::memset(keys.get(), 0, sizeof(*keys));
            publish(State::Error, 0, "cancelled");
            InterlockedExchange(&g.running, 0);
            return 0;
        }
        // Hard-cap the number of archives we even attempt (previous
        // code only counted successes, so 176 refusals from the full
        // retail install walked every archive and bloated the log).
        if (attempted >= kMaxArchivesToTry) break;
        ++attempted;

        std::uint64_t full_size = file_size_bytes(path);
        if (full_size == 0) {
            thor_log("[atlas] skip %s (file_size failed)",
                     narrow(path).c_str());
            continue;
        }
        auto prefix = read_file_prefix(path, kTocPrefixBytes);
        if (prefix.empty()) {
            thor_log("[atlas] skip %s (prefix read failed)",
                     narrow(path).c_str());
            continue;
        }
        std::string label = narrow(path);
        std::size_t last = label.find_last_of("/\\");
        std::string leaf = last == std::string::npos ? label : label.substr(last + 1);
        thor_log("[atlas] inspecting %s (file=%llu bytes, prefix=%llu bytes)",
                 leaf.c_str(),
                 static_cast<unsigned long long>(full_size),
                 static_cast<unsigned long long>(prefix.size()));

        // Log first 16 bytes so we can see the magic + encryption
        // field even when rpf7::open refuses. "RPF7" is `37 46 50 52`
        // at offset 0; Enhanced Edition's RPF8 would be `38 46 50 52`.
        if (prefix.size() >= 16) {
            thor_log("[atlas]   head: %02x %02x %02x %02x %02x %02x %02x %02x "
                     "%02x %02x %02x %02x %02x %02x %02x %02x",
                     prefix[0],  prefix[1],  prefix[2],  prefix[3],
                     prefix[4],  prefix[5],  prefix[6],  prefix[7],
                     prefix[8],  prefix[9],  prefix[10], prefix[11],
                     prefix[12], prefix[13], prefix[14], prefix[15]);
        }

        thorbridge::rpf7::Archive a;
        // self_size_bytes passes the WHOLE-FILE size (not the prefix
        // size) so NG derivation matches what the retail game would
        // compute. uint32 truncation is fine; no retail archive is
        // over 4 GB.
        if (!thorbridge::rpf7::open(prefix.data(), prefix.size(),
                                     leaf.c_str(),
                                     static_cast<std::uint32_t>(full_size),
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
                probe_minimap_archive(path, e, keys_ok ? keys.get() : nullptr);
            }
        }
    }

    // Scrub the extracted key material before we return control.
    std::memset(keys.get(), 0, sizeof(*keys));

    thor_log("[atlas] probe worker exiting (opened %llu / tried %llu / "
             "total %llu archives)",
             static_cast<unsigned long long>(touched),
             static_cast<unsigned long long>(attempted),
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
