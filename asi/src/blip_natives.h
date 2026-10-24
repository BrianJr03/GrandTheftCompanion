#pragma once

#include <cstdint>

// game:: — the script natives blips.cpp needs, as plain typed functions,
// split out for the same reason as weapon_natives.h: blips.cpp then
// holds only logic and asi/test/ can run it against fakes.
//
// Signatures follow FusionFix source/natives.ixx. Every call must run
// on the game thread while natives::ok_to_invoke(); the real
// definitions in blip_natives.cpp are natives::Context marshalling.

namespace game {

// GET_FIRST_BLIP_INFO_ID(uint type) / GET_NEXT_BLIP_INFO_ID(uint type)
// -> Blip. Walks every blip of one type; 0 ends the walk. The type is
// the blip's kind (1 car, 2 char, 3 object, 4 coord, 5 contact,
// 6 pickup, ...), NOT its sprite; read the sprite per blip.
int32_t get_first_blip_info_id(uint32_t type);
int32_t get_next_blip_info_id(uint32_t type);

// DOES_BLIP_EXIST(Blip) -> bool.
bool does_blip_exist(int32_t blip);

// GET_BLIP_SPRITE / GET_BLIP_INFO_ID_DISPLAY / GET_BLIP_INFO_ID_TYPE
// (Blip) -> uint, direct returns.
uint32_t get_blip_sprite(int32_t blip);
uint32_t get_blip_info_id_display(int32_t blip);
uint32_t get_blip_info_id_type(int32_t blip);

// GET_BLIP_COLOUR(Blip, uint* colour): out-param.
uint32_t get_blip_colour(int32_t blip);

// GET_BLIP_COORDS(Blip, Vector3* out). False if the native could not
// run; the three floats are then untouched.
bool get_blip_coords(int32_t blip, float* x, float* y, float* z);

// GET_CHAR_COORDINATES(Ped, float* x, float* y, float* z).
bool get_char_coordinates(uint32_t ped, float* x, float* y, float* z);

// GET_CHAR_HEADING(Ped, float* out). Degrees, 0 = north. The ped's own
// facing, not a camera direction. Shared with cheats (facing-aware
// spawn offset) and with probes.cpp (atlas player triangle); living in
// blip_natives means both the stock and cheats builds compile the same
// wrapper.
float get_char_heading(uint32_t ped);

// GET_MISSION_FLAG() -> bool: a mission script is running.
bool get_mission_flag();

// SET_ROUTE(Blip, bool): draw (or stop drawing) the GPS route to an
// existing blip. The blip belongs to whichever script made it; this
// only toggles its route flag.
void set_route(int32_t blip, bool on);

}  // namespace game
