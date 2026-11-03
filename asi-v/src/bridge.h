#pragma once

#include "version_info.h"

// bridge:: — in-process TCP server publishing newline-delimited JSON on
// 127.0.0.1:47801, the same port and framing as the GTA IV plugin
// (asi/src/bridge.h) so the companion app needs one client for both
// games. Every frame carries "game":"v" so the app can tell them apart.
//
// Server -> client frames:
//   hello     once per connection: exe identity + Script Hook V status
//   tick      heartbeat while no game state has been published yet
//   state     20 Hz game state (see format_state in bridge.cpp)
//   weapons   owned-weapon list; on connect and whenever it changes
//   stations  radio-station list; on connect and whenever it changes
//   blips     map blips, nearest first; on connect and whenever the
//             census result changes (see format_blips in bridge.cpp)
//   mods      Los Santos Customs page; versioned, re-sent on change
//   armory    Ammu-Nation page for the last-requested weapon;
//             versioned, re-sent on change (see format_armory). The
//             weapon hash rides under "weapon_hash" so the same Long
//             field on the client model catches both state and armory
//             frames.
//   wardrobe  Clothing + prop catalogue for the current ped;
//             versioned, re-sent on change. One row per non-empty slot;
//             a row's `prop` field disambiguates component vs prop.
//   ack       reply to a client command
//
// Client -> server:
//   {"v":1,"type":"cmd","id":N,"name":"set_radio","args":{"station":I}}
//   {"v":1,"type":"cmd","id":N,"name":"set_weapon","args":{"weapon":H}}
//   {"v":1,"type":"cmd","id":N,"name":"route_blip","args":{"blip":B}}
//   {"v":1,"type":"cmd","id":N,"name":"route_clear"}
//   {"v":1,"type":"cmd","id":N,"name":"blip_census"}
//   {"v":1,"type":"cmd","id":N,"name":"mods_list","args":{"v":0}}
//   {"v":1,"type":"cmd","id":N,"name":"set_mod",
//      "args":{"mod":(slot<<16)|index}}  (index 0xFFFF = stock / -1)
//   {"v":1,"type":"cmd","id":N,"name":"toggle_mod","args":{"slot":S}}
//   {"v":1,"type":"cmd","id":N,"name":"set_wheel_type","args":{"type":T}}
//   {"v":1,"type":"cmd","id":N,"name":"set_tint","args":{"tint":T}}
//   {"v":1,"type":"cmd","id":N,"name":"set_plate_style","args":{"style":S}}
//   {"v":1,"type":"cmd","id":N,"name":"set_plate","args":{"text":"..."}}
//   {"v":1,"type":"cmd","id":N,"name":"set_neon","args":{"rgb":0x00RRGGBB}}
//   {"v":1,"type":"cmd","id":N,"name":"set_smoke","args":{"rgb":0x00RRGGBB}}
//   {"v":1,"type":"cmd","id":N,"name":"set_xenon_colour","args":{"index":I}}
//   {"v":1,"type":"cmd","id":N,"name":"set_bulletproof_tyres","args":{"on":0|1}}
//
//   Ammu-Nation. All four refuse unless the player is on foot in a
//   non-online session; the server tracks the "current weapon" as the
//   last armory_list request so set_* work without re-sending the hash.
//   {"v":1,"type":"cmd","id":N,"name":"armory_list","args":{"weapon":HASH}}
//      Request the Ammu-Nation page for an owned weapon. Even weapons
//      with no DLC-catalogue entry (knife, grenade, vanilla pistol)
//      get a frame back with widx=-1 and an empty option list.
//   {"v":1,"type":"cmd","id":N,"name":"set_component",
//      "args":{"arg":(widx<<16)|(cidx<<1)|on}}
//      Fit (on=1) or remove (on=0) a component from the current page's
//      weapon. widx is the DLC weapon index published in the frame;
//      cidx is the DLC component index. Default components (WCT_CLIP1
//      style) cannot be removed and the server refuses on=0 for them.
//   {"v":1,"type":"cmd","id":N,"name":"set_weapon_tint",
//      "args":{"arg":(widx<<16)|tint}}
//      Set the tint index on the current page's weapon.
//   {"v":1,"type":"cmd","id":N,"name":"set_ammo",
//      "args":{"arg":(widx<<16)|action}}
//      action = 0 fills to GET_MAX_AMMO; action = 1 adds one clip
//      (clamped to the cap).
//
//   Wardrobe. Each piece is packed into one u32:
//      bit 24     1 = prop, 0 = component
//      bits 20-23 slot id (component 0..11 or prop 0..7)
//      bits 8-19  drawable (0..4094); 0xFFF on a prop = none (clear)
//      bits 0-7   texture
//   {"v":1,"type":"cmd","id":N,"name":"wardrobe_list","args":{"v":0}}
//      Request a fresh catalogue of the current ped (slots, counts).
//   {"v":1,"type":"cmd","id":N,"name":"set_piece","args":{"piece":P}}
//      Apply one packed piece. Validated with
//      IS_PED_COMPONENT_VARIATION_VALID (components) or the prop
//      counts; a bad piece is refused with "bad piece".
//   {"v":1,"type":"cmd","id":N,"name":"apply_outfit",
//      "args":{"parts":[P, P, ...]}}
//      Apply a whole outfit atomically. Max 24 entries; every part is
//      validated before any native is called, so one bad part refuses
//      the whole outfit with "bad part N" and nothing on the ped
//      changes.
//   {"v":1,"type":"cmd","id":N,"name":"reset_outfit","args":{"v":0}}
//      SET_PED_DEFAULT_COMPONENT_VARIATION + clear every prop.
//
// H is the weapon's joaat hash as an UNSIGNED decimal (0..4294967295).
// About half of all weapon hashes are above 2^31, so a client that
// formats it from a signed 32-bit int sends garbage.

namespace bridge {

constexpr const char* kPluginVersion = "0.7.0";

// Spawn the listener thread. Returns immediately.
void start(const VersionInfo& vi);

}  // namespace bridge
