#include "blip_natives.h"

#include "natives.h"

namespace {

// Direct-return natives leave their result in slot 0 of the context,
// which still holds the first argument when invoke() refuses to run
// (paused, no native table yet); never read slot 0 in that case.
uint32_t direct_u32(uint32_t hash, int32_t blip) {
    natives::Context ctx;
    ctx.push(blip);
    if (!natives::invoke(hash, ctx)) return 0;
    return ctx.get_u32();
}

}  // namespace

namespace game {

int32_t get_first_blip_info_id(uint32_t type) {
    natives::Context ctx;
    ctx.push(type);
    if (!natives::invoke(natives::GET_FIRST_BLIP_INFO_ID, ctx)) return 0;
    return ctx.get_i32();
}

int32_t get_next_blip_info_id(uint32_t type) {
    natives::Context ctx;
    ctx.push(type);
    if (!natives::invoke(natives::GET_NEXT_BLIP_INFO_ID, ctx)) return 0;
    return ctx.get_i32();
}

bool does_blip_exist(int32_t blip) {
    natives::Context ctx;
    ctx.push(blip);
    if (!natives::invoke(natives::DOES_BLIP_EXIST, ctx)) return false;
    return ctx.get_bool();
}

uint32_t get_blip_sprite(int32_t blip) {
    return direct_u32(natives::GET_BLIP_SPRITE, blip);
}

uint32_t get_blip_info_id_display(int32_t blip) {
    return direct_u32(natives::GET_BLIP_INFO_ID_DISPLAY, blip);
}

uint32_t get_blip_info_id_type(int32_t blip) {
    return direct_u32(natives::GET_BLIP_INFO_ID_TYPE, blip);
}

uint32_t get_blip_colour(int32_t blip) {
    uint32_t out = 0;
    natives::Context ctx;
    ctx.push(blip);
    ctx.push(&out);
    natives::invoke(natives::GET_BLIP_COLOUR, ctx);
    return out;
}

bool get_blip_coords(int32_t blip, float* x, float* y, float* z) {
    // rage::Vector3 is 16 bytes, 16-aligned; the native writes x, y, z
    // into the first three floats.
    alignas(16) float v[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    natives::Context ctx;
    ctx.push(blip);
    ctx.push(static_cast<void*>(v));
    if (!natives::invoke(natives::GET_BLIP_COORDS, ctx)) return false;
    *x = v[0];
    *y = v[1];
    *z = v[2];
    return true;
}

bool get_char_coordinates(uint32_t ped, float* x, float* y, float* z) {
    float ox = 0.0f, oy = 0.0f, oz = 0.0f;
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(&ox);
    ctx.push(&oy);
    ctx.push(&oz);
    if (!natives::invoke(natives::GET_CHAR_COORDINATES, ctx)) return false;
    *x = ox;
    *y = oy;
    *z = oz;
    return true;
}

bool get_mission_flag() {
    natives::Context ctx;
    if (!natives::invoke(natives::GET_MISSION_FLAG, ctx)) return false;
    return ctx.get_bool();
}

void set_route(int32_t blip, bool on) {
    natives::Context ctx;
    ctx.push(blip);
    ctx.push(static_cast<uint32_t>(on ? 1 : 0));
    natives::invoke(natives::SET_ROUTE, ctx);
}

}  // namespace game
