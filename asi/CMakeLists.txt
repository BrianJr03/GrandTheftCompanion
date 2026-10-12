cmake_minimum_required(VERSION 3.20)
project(ThorBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(ThorBridge SHARED
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
set(THORBRIDGE_NATIVES_LEVEL "4" CACHE STRING "Which native groups on_frame invokes")
if(THORBRIDGE_ENABLE_HOOK)
    target_compile_definitions(ThorBridge PRIVATE THORBRIDGE_ENABLE_HOOK=1)
endif()
if(THORBRIDGE_HOOK_NO_NATIVES)
    target_compile_definitions(ThorBridge PRIVATE THORBRIDGE_HOOK_NO_NATIVES=1)
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
