#include "cheat_natives.h"

#include "natives.h"

namespace game {

uint32_t get_player_id() {
    natives::Context ctx;
    if (!natives::invoke(natives::GET_PLAYER_ID, ctx)) return 0xFFFFFFFFu;
    return ctx.get_u32();
}

uint32_t get_car_char_is_using(uint32_t ped) {
    uint32_t veh = 0;
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(&veh);
    natives::invoke(natives::GET_CAR_CHAR_IS_USING, ctx);
    return veh;
}

void set_char_health(uint32_t ped, uint32_t health) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(health);
    natives::invoke(natives::SET_CHAR_HEALTH, ctx);
}

void add_armour_to_char(uint32_t ped, uint32_t amount) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(amount);
    natives::invoke(natives::ADD_ARMOUR_TO_CHAR, ctx);
}

uint32_t get_max_wanted_level() {
    uint32_t out = 0;
    natives::Context ctx;
    ctx.push(&out);
    natives::invoke(natives::GET_MAX_WANTED_LEVEL, ctx);
    return out;
}

void alter_wanted_level(uint32_t player, uint32_t level) {
    natives::Context ctx;
    ctx.push(player);
    ctx.push(level);
    natives::invoke(natives::ALTER_WANTED_LEVEL, ctx);
}

void apply_wanted_level_change_now(uint32_t player) {
    natives::Context ctx;
    ctx.push(player);
    natives::invoke(natives::APPLY_WANTED_LEVEL_CHANGE_NOW, ctx);
}

void clear_wanted_level(uint32_t player) {
    natives::Context ctx;
    ctx.push(player);
    natives::invoke(natives::CLEAR_WANTED_LEVEL, ctx);
}

void give_weapon_to_char(uint32_t ped, uint32_t weapon, uint32_t ammo) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(weapon);
    ctx.push(ammo);
    ctx.push(static_cast<uint32_t>(0));  // not forced into hand
    natives::invoke(natives::GIVE_WEAPON_TO_CHAR, ctx);
}

void set_char_ammo(uint32_t ped, uint32_t weapon, uint32_t ammo) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(weapon);
    ctx.push(ammo);
    natives::invoke(natives::SET_CHAR_AMMO, ctx);
}

void fix_car(uint32_t vehicle) {
    natives::Context ctx;
    ctx.push(vehicle);
    natives::invoke(natives::FIX_CAR, ctx);
}

void wash_vehicle_textures(uint32_t vehicle, uint32_t intensity) {
    natives::Context ctx;
    ctx.push(vehicle);
    ctx.push(intensity);
    natives::invoke(natives::WASH_VEHICLE_TEXTURES, ctx);
}

void change_car_colour(uint32_t vehicle, uint32_t c1, uint32_t c2) {
    natives::Context ctx;
    ctx.push(vehicle);
    ctx.push(c1);
    ctx.push(c2);
    natives::invoke(natives::CHANGE_CAR_COLOUR, ctx);
}

void get_car_colours(uint32_t vehicle, uint32_t* c1, uint32_t* c2) {
    natives::Context ctx;
    ctx.push(vehicle);
    ctx.push(c1);
    ctx.push(c2);
    natives::invoke(natives::GET_CAR_COLOURS, ctx);
}

void force_weather_now(uint32_t weather) {
    natives::Context ctx;
    ctx.push(weather);
    natives::invoke(natives::FORCE_WEATHER_NOW, ctx);
}

void release_weather() {
    natives::Context ctx;
    natives::invoke(natives::RELEASE_WEATHER, ctx);
}

void get_time_of_day(uint32_t* hour, uint32_t* minute) {
    natives::Context ctx;
    ctx.push(hour);
    ctx.push(minute);
    natives::invoke(natives::GET_TIME_OF_DAY, ctx);
}

void set_time_of_day(uint32_t hour, uint32_t minute) {
    natives::Context ctx;
    ctx.push(hour);
    ctx.push(minute);
    natives::invoke(natives::SET_TIME_OF_DAY, ctx);
}

void set_player_invincible(uint32_t player, bool on) {
    natives::Context ctx;
    ctx.push(player);
    ctx.push(static_cast<uint32_t>(on ? 1 : 0));
    natives::invoke(natives::SET_PLAYER_INVINCIBLE, ctx);
}

void set_player_fast_reload(uint32_t player, bool on) {
    natives::Context ctx;
    ctx.push(player);
    ctx.push(static_cast<uint32_t>(on ? 1 : 0));
    natives::invoke(natives::SET_PLAYER_FAST_RELOAD, ctx);
}

void set_player_never_gets_tired(uint32_t player, bool on) {
    natives::Context ctx;
    ctx.push(player);
    ctx.push(static_cast<uint32_t>(on ? 1 : 0));
    natives::invoke(natives::SET_PLAYER_NEVER_GETS_TIRED, ctx);
}

void set_time_scale(float scale) {
    natives::Context ctx;
    ctx.push_float(scale);
    natives::invoke(natives::SET_TIME_SCALE, ctx);
}

void set_police_ignore_player(uint32_t player, bool on) {
    natives::Context ctx;
    ctx.push(player);
    ctx.push(static_cast<uint32_t>(on ? 1 : 0));
    natives::invoke(natives::SET_POLICE_IGNORE_PLAYER, ctx);
}

// --- vehicle spawning --------------------------------------------

uint32_t get_hash_key(const char* name) {
    natives::Context ctx;
    ctx.push(const_cast<char*>(name));
    if (!natives::invoke(natives::GET_HASH_KEY, ctx)) return 0;
    return ctx.get_u32();
}

void request_model(uint32_t model_hash) {
    natives::Context ctx;
    ctx.push(model_hash);
    natives::invoke(natives::REQUEST_MODEL, ctx);
}

bool has_model_loaded(uint32_t model_hash) {
    natives::Context ctx;
    ctx.push(model_hash);
    if (!natives::invoke(natives::HAS_MODEL_LOADED, ctx)) return false;
    return ctx.get_bool();
}

void mark_model_as_no_longer_needed(uint32_t model_hash) {
    natives::Context ctx;
    ctx.push(model_hash);
    natives::invoke(natives::MARK_MODEL_AS_NO_LONGER_NEEDED, ctx);
}

void create_car(uint32_t model_hash, float x, float y, float z,
                uint32_t* out_vehicle) {
    natives::Context ctx;
    ctx.push(model_hash);
    ctx.push_float(x);
    ctx.push_float(y);
    ctx.push_float(z);
    ctx.push(out_vehicle);
    ctx.push(static_cast<uint32_t>(1));  // unknownTrue
    natives::invoke(natives::CREATE_CAR, ctx);
}

void warp_char_into_car(uint32_t ped, uint32_t vehicle) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(vehicle);
    natives::invoke(natives::WARP_CHAR_INTO_CAR, ctx);
}

void mark_car_as_no_longer_needed(uint32_t* vehicle_ptr) {
    natives::Context ctx;
    ctx.push(vehicle_ptr);
    natives::invoke(natives::MARK_CAR_AS_NO_LONGER_NEEDED, ctx);
}

// NOTE: get_char_heading moved to blip_natives.cpp so stock (non-cheats)
// builds can read the player's heading for the atlas page's player
// triangle. It stayed logically here through the cheats-first phases.

void set_char_heading(uint32_t ped, float heading) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push_float(heading);
    natives::invoke(natives::SET_CHAR_HEADING, ctx);
}

// get_char_coordinates: see blip_natives.cpp — the same wrapper is
// used both for the level-5 blip census and for cheats::advance_spawn.

}  // namespace game
