#pragma once

// hook:: — install a trampoline on GTA IV's CGameProcess (the per-frame
// tick that drives the script engine). FusionFix does the same thing in
// source/dllmain.cpp — see NOTICE.
//
// Call install() exactly once, after the deferred-init gate pattern has
// matched (i.e. the game's code is decrypted and settled). The user
// callback runs AFTER the game's own CGameProcess completes each frame,
// on the same thread, with the game's script-engine state consistent.

namespace hook {

using FrameCallback = void (*)();

// Patch CGameProcess's 5-byte CALL site to detour through a stub that
// first calls the original CGameProcess and then calls `cb`. Returns
// false (and logs) if the pattern fails to resolve or VirtualProtect
// refuses to make the site writable. Idempotent — a second call just
// returns true.
bool install_cgameprocess(FrameCallback cb);

}  // namespace hook
