#pragma once

#include <cstdint>

// Jenkins one-at-a-time, the hash RAGE uses for model / weapon / stat
// names. Case-insensitive on ASCII, matching the game's own behaviour
// (GET_HASH_KEY("PLAYER_ZERO") == GET_HASH_KEY("player_zero")).
//
// constexpr so every table in this plugin is spelled as a *name* and
// hashed at compile time. No hand-typed hex constants to get wrong.
constexpr uint32_t joaat(const char* s) {
    uint32_t h = 0;
    for (; *s; ++s) {
        char c = *s;
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        h += static_cast<uint8_t>(c);
        h += h << 10;
        h ^= h >> 6;
    }
    h += h << 3;
    h ^= h >> 11;
    h += h << 15;
    return h;
}

// Published reference values; a wrong shift above fails the build.
static_assert(joaat("player_zero") == 0x0D7114C9u, "joaat self-test (Michael)");
static_assert(joaat("player_one")  == 0x9B22DBAFu, "joaat self-test (Franklin)");
static_assert(joaat("player_two")  == 0x9B810FA2u, "joaat self-test (Trevor)");
static_assert(joaat("weapon_pistol") == 0x1B06D571u, "joaat self-test (pistol)");
