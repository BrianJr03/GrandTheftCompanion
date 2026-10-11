#include "shv.h"

#include <cstdio>

#include "log.h"

namespace {

// Signatures as declared by the Script Hook V SDK (inc/main.h). x64
// Windows has a single calling convention, so plain function pointers
// are correct regardless of which compiler built either side.
using FnScriptRegister   = void (*)(HMODULE, void (*)());
using FnScriptUnregister = void (*)(HMODULE);
using FnScriptWait       = void (*)(DWORD);
using FnNativeInit       = void (*)(UINT64);
using FnNativePush64     = void (*)(UINT64);
using FnNativeCall       = PUINT64 (*)();
using FnGetGameVersion   = int (*)();

HMODULE            g_mod = nullptr;
bool               g_attempted = false;
bool               g_ok = false;
char               g_err[160] = {0};

FnScriptRegister   g_script_register = nullptr;
FnScriptUnregister g_script_unregister = nullptr;
FnScriptWait       g_script_wait = nullptr;
FnNativeInit       g_native_init = nullptr;
FnNativePush64     g_native_push = nullptr;
FnNativeCall       g_native_call = nullptr;
FnGetGameVersion   g_get_game_version = nullptr;

// Walk `mod`'s PE export directory for the first export whose name
// starts with `prefix` and (if `must_contain` is set) also contains
// that substring. Returns nullptr when nothing matches.
FARPROC find_export_by_prefix(HMODULE mod, const char* prefix,
                              const char* must_contain,
                              char* matched, size_t matched_cap) {
    auto* base = reinterpret_cast<const BYTE*>(mod);
    auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return nullptr;
    auto* nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return nullptr;

    const IMAGE_DATA_DIRECTORY& dir =
        nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];
    if (dir.VirtualAddress == 0 || dir.Size == 0) return nullptr;

    auto* exp = reinterpret_cast<const IMAGE_EXPORT_DIRECTORY*>(
        base + dir.VirtualAddress);
    auto* names = reinterpret_cast<const DWORD*>(base + exp->AddressOfNames);
    const size_t prefix_len = std::strlen(prefix);

    for (DWORD i = 0; i < exp->NumberOfNames; ++i) {
        const char* name = reinterpret_cast<const char*>(base + names[i]);
        if (std::strncmp(name, prefix, prefix_len) != 0) continue;
        if (must_contain && !std::strstr(name, must_contain)) continue;
        // Resolve through GetProcAddress so forwarders are honoured.
        FARPROC p = GetProcAddress(mod, name);
        if (!p) continue;
        if (matched && matched_cap) {
            std::snprintf(matched, matched_cap, "%s", name);
        }
        return p;
    }
    return nullptr;
}

// Resolve one export: exact decorated name, then decorated-prefix
// search, then the bare C name. Logs which form matched.
template <typename Fn>
bool resolve(Fn* out, const char* plain, const char* decorated,
             const char* must_contain = nullptr) {
    FARPROC p = GetProcAddress(g_mod, decorated);
    const char* how = "exact";
    char matched[160] = {0};

    if (!p) {
        char prefix[64];
        std::snprintf(prefix, sizeof(prefix), "?%s@@", plain);
        p = find_export_by_prefix(g_mod, prefix, must_contain,
                                  matched, sizeof(matched));
        how = "prefix";
    }
    if (!p) {
        p = GetProcAddress(g_mod, plain);
        how = "plain";
    }
    if (!p) {
        thor_log("[shv] export %s NOT found", plain);
        if (!g_err[0]) {
            std::snprintf(g_err, sizeof(g_err),
                          "ScriptHookV.dll has no %s export", plain);
        }
        return false;
    }
    if (std::strcmp(how, "exact") != 0) {
        thor_log("[shv] export %s resolved via %s match (%s)",
                 plain, how, matched[0] ? matched : plain);
    }
    // FARPROC -> typed function pointer. memcpy sidesteps
    // -Wcast-function-type; both are plain code pointers.
    std::memcpy(out, &p, sizeof(p));
    return true;
}

}  // namespace

namespace shv {

bool load() {
    if (g_attempted) return g_ok;
    g_attempted = true;

    // Usually already mapped: any other Script Hook V plugin in the
    // folder imports it statically. If we are the only plugin, this is
    // what pulls it in.
    g_mod = GetModuleHandleA("ScriptHookV.dll");
    if (!g_mod) g_mod = LoadLibraryA("ScriptHookV.dll");
    if (!g_mod) {
        DWORD e = GetLastError();
        std::snprintf(g_err, sizeof(g_err),
                      "ScriptHookV.dll not loaded (error %lu)", e);
        thor_log("[shv] %s — is Script Hook V installed next to GTA5.exe?",
                 g_err);
        return false;
    }

    bool ok = true;
    ok &= resolve(&g_script_register, "scriptRegister",
                  "?scriptRegister@@YAXPEAUHINSTANCE__@@P6AXXZ@Z");
    // scriptUnregister is overloaded (HMODULE and a deprecated
    // function-pointer form); insist on the HMODULE one.
    ok &= resolve(&g_script_unregister, "scriptUnregister",
                  "?scriptUnregister@@YAXPEAUHINSTANCE__@@@Z",
                  "HINSTANCE__@@@Z");
    ok &= resolve(&g_script_wait, "scriptWait", "?scriptWait@@YAXK@Z");
    ok &= resolve(&g_native_init, "nativeInit", "?nativeInit@@YAX_K@Z");
    ok &= resolve(&g_native_push, "nativePush64", "?nativePush64@@YAX_K@Z");
    ok &= resolve(&g_native_call, "nativeCall", "?nativeCall@@YAPEA_KXZ");

    // Diagnostic only — absence is not fatal.
    FnGetGameVersion ggv = nullptr;
    if (resolve(&ggv, "getGameVersion",
                "?getGameVersion@@YA?AW4eGameVersion@@XZ")) {
        g_get_game_version = ggv;
    } else {
        // resolve() recorded an error string for the miss; it is not
        // one, so clear it unless a required export already failed.
        if (ok) g_err[0] = '\0';
    }

    g_ok = ok;
    if (ok) {
        thor_log("[shv] ScriptHookV.dll bound at %p, game_version=%d",
                 static_cast<void*>(g_mod), game_version());
    } else {
        thor_log("[shv] binding incomplete: %s", g_err);
    }
    return g_ok;
}

bool available() { return g_ok; }

const char* error() { return g_err; }

int game_version() {
    return g_get_game_version ? g_get_game_version() : -1;
}

void script_register(HMODULE self, ScriptMain fn) {
    if (g_ok) g_script_register(self, fn);
}

void script_unregister(HMODULE self) {
    if (g_ok) g_script_unregister(self);
}

void script_wait(DWORD ms) {
    if (g_ok) g_script_wait(ms);
}

void native_init(uint64_t hash) { g_native_init(hash); }
void native_push(uint64_t slot) { g_native_push(slot); }
uint64_t* native_call() { return g_native_call(); }

}  // namespace shv
