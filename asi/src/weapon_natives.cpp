#include "weapon_natives.h"

#include "natives.h"

namespace game {

void get_char_weapon_in_slot(uint32_t ped, uint32_t slot, uint32_t* weapon,
                             uint32_t* ammo0, uint32_t* ammo1) {
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(slot);
    ctx.push(weapon);
    ctx.push(ammo0);
    ctx.push(ammo1);
    natives::invoke(natives::GET_CHAR_WEAPON_IN_SLOT, ctx);
}

uint32_t get_ammo_in_char_weapon(uint32_t ped, uint32_t weapon) {
    uint32_t out = 0;
    natives::Context ctx;
    ctx.push(ped);
    ctx.push(weapon);
    ctx.push(&out);
    natives::invoke(natives::GET_AMMO_IN_CHAR_WEAPON, ctx);
    return out;
}

bool is_button_pressed(uint32_t pad, uint32_t button) {
    natives::Context ctx;
    ctx.push(pad);
    ctx.push(button);
    // invoke() refuses while paused or before the native table exists;
    // slot 0 would then still hold `pad`, so do not read it.
    if (!natives::invoke(natives::IS_BUTTON_PRESSED, ctx)) return false;
    return ctx.get_bool();
}

}  // namespace game
