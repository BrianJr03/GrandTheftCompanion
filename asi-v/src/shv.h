#pragma once

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstring>
#include <type_traits>

// shv:: — runtime binding to Script Hook V (ScriptHookV.dll, by
// Alexander Blade; closed-source freeware, NOT bundled with this
// project and not linked at build time).
//
// Why runtime binding instead of the official SDK's import library:
//   * The SDK's ScriptHookV.lib is an MSVC import library for C++-
//     decorated exports; this project cross-compiles with mingw-w64,
//     whose C++ name decoration differs.
//   * Nothing from the SDK needs to live in this repository.
//   * A missing or renamed export becomes a logged, recoverable error
//     ("safe mode": the bridge still answers with a hello frame that
//     says what is wrong) instead of Windows refusing to load the ASI
//     with no diagnostics at all.
//
// load() resolves each export first by its exact MSVC-decorated name
// and, failing that, by walking ScriptHookV.dll's export table for a
// `?<name>@@` prefix match, so a signature tweak in a future Script
// Hook V release degrades gracefully.

namespace shv {

// LoadLibrary("ScriptHookV.dll") + resolve exports. Idempotent.
// Returns true when every export this plugin needs was found.
bool load();

// True after a successful load().
bool available();

// Human-readable reason load() failed ("" on success / not attempted).
const char* error();

// Raw eGameVersion value reported by Script Hook V, or -1 if unknown.
// Logged and forwarded in the hello frame for diagnostics only; the
// plugin never branches on it.
int game_version();

// --- script lifecycle -------------------------------------------------
using ScriptMain = void (*)();
void script_register(HMODULE self, ScriptMain fn);
void script_unregister(HMODULE self);
// Yield the script fiber back to the game for `ms` (0 = next frame).
// Only valid on the script fiber Script Hook V created for `fn`.
void script_wait(DWORD ms);

// --- raw native invocation --------------------------------------------
void      native_init(uint64_t hash);
void      native_push(uint64_t slot);
uint64_t* native_call();

// --- typed native invocation ------------------------------------------
//
//   int ped = shv::call<int>(natives::PLAYER_PED_ID);
//   float v = shv::call<float>(natives::GET_ENTITY_SPEED, veh);
//
// Every argument occupies one 8-byte script slot: the value is copied
// into the low bytes of a zeroed slot, which is how the game's own
// script VM passes ints, floats, BOOLs, hashes and pointers alike.
// MUST only be called on the script fiber, and only when available().
template <typename T>
inline uint64_t to_slot(T v) {
    static_assert(sizeof(T) <= sizeof(uint64_t), "native arg wider than a slot");
    static_assert(std::is_trivially_copyable<T>::value, "native arg not POD");
    uint64_t slot = 0;
    std::memcpy(&slot, &v, sizeof(T));
    return slot;
}

template <typename R, typename... Args>
inline R call(uint64_t hash, Args... args) {
    native_init(hash);
    (native_push(to_slot(args)), ...);
    uint64_t* ret = native_call();
    if constexpr (std::is_void<R>::value) {
        (void)ret;
        return;
    } else {
        static_assert(sizeof(R) <= sizeof(uint64_t), "native return wider than a slot");
        R out{};
        if (ret) std::memcpy(&out, ret, sizeof(R));
        return out;
    }
}

// BOOL-returning natives fill the whole slot on some builds and only
// the low dword on others; compare the low 32 bits to be safe.
template <typename... Args>
inline bool call_bool(uint64_t hash, Args... args) {
    return call<int32_t>(hash, args...) != 0;
}

// Vector3-returning natives write three floats into three consecutive
// return slots (the script VM's Vector3 is three 8-byte-padded floats,
// not a packed struct), so a plain call<> would only read x.
struct Vec3 {
    float x, y, z;
};

template <typename... Args>
inline Vec3 call_vec3(uint64_t hash, Args... args) {
    native_init(hash);
    (native_push(to_slot(args)), ...);
    uint64_t* ret = native_call();
    Vec3 v{};
    if (ret) {
        std::memcpy(&v.x, &ret[0], sizeof(float));
        std::memcpy(&v.y, &ret[1], sizeof(float));
        std::memcpy(&v.z, &ret[2], sizeof(float));
    }
    return v;
}

}  // namespace shv
