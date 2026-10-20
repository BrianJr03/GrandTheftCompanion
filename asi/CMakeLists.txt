cmake_minimum_required(VERSION 3.20)
project(ThorBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(ThorBridge SHARED
    src/blip_natives.cpp
    src/blips.cpp
    src/bridge.cpp
    src/commands.cpp
    src/dllmain.cpp
    src/hook.cpp
    src/log.cpp
    src/natives.cpp
    src/patterns.cpp
    src/probes.cpp
    src/snapshot.cpp
    src/version_detect.cpp
    src/weapon_natives.cpp
    src/weapons.cpp
)

target_compile_options(ThorBridge PRIVATE
    -Wall -Wextra -Wpedantic
    -fno-exceptions
)

# Round-2+ safety flags. Default: hook OFF, natives OFF. Switch on the
# command line, e.g.:
#   cmake .. -DTHORBRIDGE_ENABLE_HOOK=ON              # hook on, natives on
#   cmake .. -DTHORBRIDGE_ENABLE_HOOK=ON \
#            -DTHORBRIDGE_HOOK_NO_NATIVES=ON          # hook on, natives off
option(THORBRIDGE_ENABLE_HOOK    "Install the CGameProcess per-frame hook" OFF)
option(THORBRIDGE_HOOK_NO_NATIVES "If hook is on, skip native invocations" OFF)
# 1 = core stats only (health/armor/money/wanted)
# 2 = stats + current weapon + ammo (no owned-slots scan)
# 3 = stats + weapon + vehicle + radio — the full HUD
# 4 = 3 + owned-weapon scan + LB poll, for the companion's weapon wheel.
#     Default after on-device validation; the scan's first incarnation
#     crashed the game, see src/weapons.h for the slot cap that fixed it.
# 5 = 4 + map blip census (every 2 s) + GPS route commands, for routing
#     to missions from the companion. Not yet validated on a device;
#     CI and the default stay at 4 until it is.
set(THORBRIDGE_NATIVES_LEVEL "4" CACHE STRING "Which native groups on_frame invokes")
# Independent of the level: enable the `cheat` command, which lets the
# companion write player/wanted/weather/clock state through natives. OFF
# by default and OFF in CI so the public build keeps shipping bytes that
# never write game state. Device-test build is level 4 + cheats ON.
option(THORBRIDGE_CHEATS "Enable the cheat command (writes player/world state)" OFF)
if(THORBRIDGE_ENABLE_HOOK)
    target_compile_definitions(ThorBridge PRIVATE THORBRIDGE_ENABLE_HOOK=1)
endif()
if(THORBRIDGE_HOOK_NO_NATIVES)
    target_compile_definitions(ThorBridge PRIVATE THORBRIDGE_HOOK_NO_NATIVES=1)
endif()
if(THORBRIDGE_CHEATS)
    target_compile_definitions(ThorBridge PRIVATE THORBRIDGE_CHEATS=1)
    # cheats.cpp / cheat_natives.cpp are only compiled into the DLL
    # when the flag is on. The CI build (flag off) keeps emitting the
    # same code bytes as before this change — the only user-visible
    # difference is the new `cheats:false` field in the hello frame.
    target_sources(ThorBridge PRIVATE
        src/cheat_natives.cpp
        src/cheats.cpp
    )
endif()
target_compile_definitions(ThorBridge PRIVATE
    THORBRIDGE_NATIVES_LEVEL=${THORBRIDGE_NATIVES_LEVEL})

target_link_libraries(ThorBridge PRIVATE
    version   # GetFileVersionInfoA / VerQueryValueA
    ws2_32    # winsock, needed from M3 onward
)

set_target_properties(ThorBridge PROPERTIES
    PREFIX ""
    SUFFIX ".asi"
    OUTPUT_NAME "ThorBridge"
)
