#pragma once

#include <cstddef>
#include <cstdint>

// armory:: — Ammu-Nation page builder.
//
// Source A, picked after Gate A0: catalogue data comes from the game's
// own GET_DLC_WEAPON_DATA / GET_DLC_WEAPON_COMPONENT_DATA. Those
// return 71 shop-configurable weapons on this build; the vanilla
// pistol / carbine / knife / grenade are intentionally outside that
// list (they have no customizable parts). Weapons outside the list
// still get an `armory` frame published (widx=-1, no options) so the
// sheet can show "no attachments" and hide Tint/Ammo for them.
//
// Lifecycle (script fiber only):
//   on_session_reset()     — clears the hash→widx map (online, loading
//                            screen, character switch). Rebuilds on
//                            next tick when back in-game.
//   tick(ped)              — slice of session build + apply any
//                            pending armory_list request.
//   request(hash)          — ask for a weapon's page. Fast when the
//                            session map is already built; defers to
//                            the next tick otherwise.
//   apply(name, arg, ped,
//         err, cap)        — run one of the four commands on the fiber.
//                            Returns false (with err filled) when
//                            refused (bad index, default removal, out
//                            of range, etc.).

namespace armory {

// How many DLC indices the session-building walker reads per tick.
// 71 entries * ~2ms per native observed in Gate A0 would freeze the
// fiber for ~130ms if read in one slice; sliced at 8 that's 9 ticks
// (~360ms wall-clock) of ~16ms slices, within the HUD's budget.
constexpr uint32_t kSlotsPerTick = 8;

// A client asked for a weapon's page. hash is the joaat weapon hash
// published in the owned-weapons list.
void request(uint32_t hash);

// Script fiber: build / rebuild the session map and satisfy any
// pending request. Cheap when both are already resolved.
void tick(int32_t ped);

// Call when the game transitions out of a usable state (online,
// loading, character switch, player ped went away). Clears the
// published armory and the current-request bookkeeping. The session
// map is kept because the DLC catalogue is stable within a game
// build; it is only cleared by reset().
void on_session_reset();

// Script fiber: apply one armory command against the live game.
// Commands are:
//   armory_list       arg = ignored; the weapon hash rides in the
//                     frame's args.weapon (passed separately via
//                     request_hash).
//   set_component     arg = (widx<<16) | (cidx<<1) | on
//   set_weapon_tint   arg = (widx<<16) | tint
//   set_ammo          arg = (widx<<16) | action   action 0=fill, 1=+clip
// Fills `err` on refusal; returns false. The caller posts the ack.
bool apply(const char* name, uint32_t arg, uint32_t request_hash,
           int32_t ped, char* err, size_t cap);

// Tests / crash-recovery: forget every bit of armory state.
void reset();

}  // namespace armory
