#pragma once

#include <cstdint>

// game:: — the GTA IV script natives the cheats subsystem (cheats.cpp)
// needs, as plain typed functions. Split out from cheats.cpp the same
// way weapon_natives.h is split out from weapons.cpp: the logic file
// stays Windows-free and can be built and tested on any host by faking
// everything in this header.
//
// Signatures follow FusionFix source/natives.ixx; the hashes they map
// to live in natives.h. Every call runs on the game thread while
// natives::ok_to_invoke() — the real wrappers forward through
// natives::invoke() which checks that itself, so callers do not have
// to.

namespace game {

// --- identity -----------------------------------------------------
// Reusing the natives probes.cpp already resolves, but given here as
// typed accessors so cheats.cpp need not know about natives::Context.
uint32_t get_player_id();
// GET_CAR_CHAR_IS_USING(Ped, Vehicle* out). 0 when the ped is not in
// a car. Mirrors weapons' convention: never crashes on an out-of-car
// ped, caller must check.
uint32_t get_car_char_is_using(uint32_t ped);

// --- stats --------------------------------------------------------
void set_char_health(uint32_t ped, uint32_t health);
void add_armour_to_char(uint32_t ped, uint32_t amount);

// --- wanted -------------------------------------------------------
// Convenience: the max the game accepts for a wanted-level write.
// Comes from GET_MAX_WANTED_LEVEL and caps at 6 on vanilla IV, but we
// read it live rather than hard-code.
uint32_t get_max_wanted_level();
void alter_wanted_level(uint32_t player, uint32_t level);
void apply_wanted_level_change_now(uint32_t player);
void clear_wanted_level(uint32_t player);

// --- loadout ------------------------------------------------------
// GIVE_WEAPON_TO_CHAR(Ped, uint weapon, uint ammo, bool holding). The
// last arg controls whether the weapon is forced into the ped's hand;
// cheats pass false so the current weapon is not disturbed.
void give_weapon_to_char(uint32_t ped, uint32_t weapon, uint32_t ammo);
void set_char_ammo(uint32_t ped, uint32_t weapon, uint32_t ammo);

// --- vehicle ------------------------------------------------------
void fix_car(uint32_t vehicle);
void wash_vehicle_textures(uint32_t vehicle, uint32_t intensity);

// --- world --------------------------------------------------------
void force_weather_now(uint32_t weather);
void release_weather();
void get_time_of_day(uint32_t* hour, uint32_t* minute);
void set_time_of_day(uint32_t hour, uint32_t minute);

// --- player toggles ----------------------------------------------
void set_player_invincible(uint32_t player, bool on);
void set_player_fast_reload(uint32_t player, bool on);
void set_player_never_gets_tired(uint32_t player, bool on);
void set_time_scale(float scale);
void set_police_ignore_player(uint32_t player, bool on);

// --- vehicle spawning --------------------------------------------
// Same across-tick state-machine pattern as the GTA V plugin:
// get_hash_key(name) once, then request + poll loaded, then create
// + warp + mark no-longer-needed. Everything runs on the game
// thread from probes.cpp per-frame hook via cheats::tick.
uint32_t get_hash_key(const char* name);
void request_model(uint32_t model_hash);
bool has_model_loaded(uint32_t model_hash);
void mark_model_as_no_longer_needed(uint32_t model_hash);
// CREATE_CAR writes a Vehicle handle through *out_vehicle.
void create_car(uint32_t model_hash, float x, float y, float z,
                uint32_t* out_vehicle);
void warp_char_into_car(uint32_t ped, uint32_t vehicle);
// Takes a pointer to a Vehicle handle — same calling convention
// as the V plugin's SET_VEHICLE_AS_NO_LONGER_NEEDED wrapper.
void mark_car_as_no_longer_needed(uint32_t* vehicle_ptr);
float get_char_heading(uint32_t ped);
// Only used to re-orient the car after SET_PED_INTO_VEHICLE if the
// game spawned it rotated.
void set_char_heading(uint32_t ped, float heading);

// NOTE: get_char_coordinates is already declared in blip_natives.h
// (used at natives level 5 for the blip census). cheats::advance_spawn
// uses that same wrapper rather than redefining it.

}  // namespace game
