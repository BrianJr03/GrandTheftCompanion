#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "patterns.h"
#include "log.h"

namespace {

// Resolve the exe's .text section (code only) once at first use. All our
// patterns point into code; scanning the whole image includes .rsrc and
// .reloc for no benefit and increases ambiguity risk.
struct TextRange {
    const uint8_t* base = nullptr;
    size_t size = 0;
};

TextRange g_text{};
bool g_text_resolved = false;

void resolve_text() {
    if (g_text_resolved) return;
    g_text_resolved = true;

    HMODULE mod = GetModuleHandleA(NULL);
    if (!mod) return;
    const uint8_t* base = reinterpret_cast<const uint8_t*>(mod);

    const IMAGE_DOS_HEADER* dos =
        reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return;

    const IMAGE_NT_HEADERS* nt =
        reinterpret_cast<const IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return;

    const IMAGE_SECTION_HEADER* sec = IMAGE_FIRST_SECTION(nt);
    for (unsigned i = 0; i < nt->FileHeader.NumberOfSections; ++i, ++sec) {
        if (std::memcmp(sec->Name, ".text", 5) == 0) {
            g_text.base = base + sec->VirtualAddress;
            g_text.size = sec->Misc.VirtualSize;
            return;
        }
    }
}

// Parse "8B 44 24 ?? 50" into bytes[] + mask[] (mask[i]=1 if real byte,
// 0 if wildcard). Returns 0 on malformed input.
size_t parse_pattern(const char* pat, uint8_t bytes[256], uint8_t mask[256]) {
    size_t n = 0;
    const char* p = pat;
    while (*p && n < 256) {
        while (*p == ' ' || *p == '\t') ++p;
        if (!*p) break;
        if (p[0] == '?' && p[1] == '?') {
            bytes[n] = 0;
            mask[n] = 0;
            ++n;
            p += 2;
            continue;
        }
        if (!std::isxdigit(static_cast<unsigned char>(p[0]))
            || !std::isxdigit(static_cast<unsigned char>(p[1]))) {
            return 0;
        }
        auto hex = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        };
        bytes[n] = static_cast<uint8_t>((hex(p[0]) << 4) | hex(p[1]));
        mask[n] = 1;
        ++n;
        p += 2;
    }
    return n;
}

bool match_at(const uint8_t* haystack, const uint8_t* bytes,
              const uint8_t* mask, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if (mask[i] && haystack[i] != bytes[i]) return false;
    }
    return true;
}

}  // namespace

namespace patterns {

uintptr_t find(const char* name, const char* pat) {
    resolve_text();
    if (!g_text.base || g_text.size == 0) {
        thor_log("[pattern] %s: .text section not resolved", name);
        return 0;
    }

    uint8_t bytes[256];
    uint8_t mask[256];
    size_t n = parse_pattern(pat, bytes, mask);
    if (n == 0) {
        thor_log("[pattern] %s: malformed pattern", name);
        return 0;
    }

    const uint8_t* base = g_text.base;
    size_t sz = g_text.size;
    if (n > sz) {
        thor_log("[pattern] %s: pattern (%zu bytes) exceeds .text (%zu)",
                 name, n, sz);
        return 0;
    }

    uintptr_t first_hit = 0;
    unsigned hit_count = 0;
    for (size_t i = 0; i + n <= sz; ++i) {
        if (match_at(base + i, bytes, mask, n)) {
            if (hit_count == 0) {
                first_hit = reinterpret_cast<uintptr_t>(base + i);
            }
            ++hit_count;
            if (hit_count > 1) {
                thor_log("[pattern] %s: ambiguous (>=2 matches); refusing",
                         name);
                return 0;
            }
        }
    }

    if (hit_count == 0) {
        thor_log("[pattern] %s: no match", name);
        return 0;
    }

    thor_log("[pattern] %s: hit at 0x%08X", name,
             static_cast<unsigned>(first_hit));
    return first_hit;
}

}  // namespace patterns
