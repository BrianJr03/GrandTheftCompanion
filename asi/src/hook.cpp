// CGameProcess 5-byte CALL overwrite. Modelled on FusionFix's
// injector::MakeCALL (source/dllmain.cpp:46-48); re-implemented with
// VirtualProtect + byte rewrites so we don't need to pull injector/ or
// safetyhook into the build. See NOTICE for GPL-3.0 attribution.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstring>

#include "hook.h"
#include "log.h"
#include "patterns.h"

namespace {

// FusionFix (dllmain.cpp:46-48): a long run of E8 displacement calls
// followed by `B9 <imm32>`. Points at the first CALL instruction, which
// is CGameProcess.
constexpr const char* kPatCGameProcessCall =
    "E8 ?? ?? ?? ?? E8 ?? ?? ?? ?? E8 ?? ?? ?? ?? B9 ?? ?? ?? ?? "
    "E8 ?? ?? ?? ?? E8 ?? ?? ?? ?? E8 ?? ?? ?? ?? E8 ?? ?? ?? ?? B9";

using VoidFn = void (*)();

VoidFn g_orig_cgameprocess = nullptr;
hook::FrameCallback g_cb = nullptr;
bool g_installed = false;

// Our replacement for CGameProcess at the hooked call site. Runs on the
// game's main/script thread. Call the original first (so game state is
// freshly updated), then our callback.
void our_cgameprocess() {
    if (g_orig_cgameprocess) g_orig_cgameprocess();
    if (g_cb) g_cb();
}

}  // namespace

namespace hook {

bool install_cgameprocess(FrameCallback cb) {
    if (g_installed) return true;
    if (!cb) return false;

    uintptr_t site = patterns::find("CGameProcess call", kPatCGameProcessCall);
    if (!site) return false;

    // Site = address of the `E8` opcode. Compute the original absolute
    // target before we scribble on it.
    int32_t orig_rel = 0;
    std::memcpy(&orig_rel, reinterpret_cast<void*>(site + 1), sizeof(orig_rel));
    uintptr_t orig_target =
        site + 5 + static_cast<uintptr_t>(static_cast<int32_t>(orig_rel));
    g_orig_cgameprocess = reinterpret_cast<VoidFn>(orig_target);
    g_cb = cb;

    DWORD old_prot = 0;
    if (!VirtualProtect(reinterpret_cast<LPVOID>(site), 5,
                        PAGE_EXECUTE_READWRITE, &old_prot)) {
        thor_log("[hook] VirtualProtect(RW) failed: %u", GetLastError());
        return false;
    }

    int32_t new_rel = static_cast<int32_t>(
        reinterpret_cast<uintptr_t>(our_cgameprocess) - site - 5);
    std::memcpy(reinterpret_cast<void*>(site + 1), &new_rel, sizeof(new_rel));

    DWORD junk = 0;
    VirtualProtect(reinterpret_cast<LPVOID>(site), 5, old_prot, &junk);
    FlushInstructionCache(GetCurrentProcess(),
                          reinterpret_cast<LPCVOID>(site), 5);

    thor_log("[hook] CGameProcess detour: site=0x%08X orig=0x%08X new_rel=%d",
             static_cast<unsigned>(site),
             static_cast<unsigned>(orig_target),
             new_rel);

    g_installed = true;
    return true;
}

}  // namespace hook
