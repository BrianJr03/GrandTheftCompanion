#pragma once

// script:: — the game-state reader. main() is handed to Script Hook V,
// which runs it on a fiber of the game's script thread; every native
// call in this plugin happens there and nowhere else.

namespace script {

// Entry point passed to shv::script_register. Never returns.
void main();

// True once main() has run at least one tick (Script Hook V accepted
// the game build and started our fiber). Surfaced in the hello frame.
bool started();

}  // namespace script
