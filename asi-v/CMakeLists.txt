cmake_minimum_required(VERSION 3.20)
project(ThorBridgeV LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(ThorBridgeV SHARED
    src/blips.cpp
    src/bridge.cpp
    src/cheats.cpp
    src/commands.cpp
    src/dllmain.cpp
    src/log.cpp
    src/mods.cpp
    src/script.cpp
    src/shv.cpp
    src/snapshot.cpp
    src/version_info.cpp
)

target_compile_options(ThorBridgeV PRIVATE
    -Wall -Wextra -Wpedantic
    -fno-exceptions
)

# Bind 0.0.0.0 instead of loopback so a dev machine on the same LAN can
# read the stream. Never ship a build with this on.
option(THORBRIDGE_LAN_DEBUG "Bind the bridge socket to 0.0.0.0" OFF)
if(THORBRIDGE_LAN_DEBUG)
    target_compile_definitions(ThorBridgeV PRIVATE THORBRIDGE_LAN_DEBUG=1)
endif()

target_link_libraries(ThorBridgeV PRIVATE
    version   # GetFileVersionInfoA / VerQueryValueA
    ws2_32    # winsock
)

set_target_properties(ThorBridgeV PROPERTIES
    PREFIX ""
    SUFFIX ".asi"
    OUTPUT_NAME "ThorBridgeV"
)

# ---------------------------------------------------------------------
# Off-device test harness (optional). Builds a stand-in ScriptHookV.dll
# that exports the same decorated names as the real one and answers
# natives with canned values, plus a tiny host exe that loads the ASI
# the way an ASI loader would. See test/README.md.
# ---------------------------------------------------------------------
option(THORBRIDGE_BUILD_MOCK "Build the mock Script Hook V + host exe" OFF)
if(THORBRIDGE_BUILD_MOCK)
    add_library(MockScriptHookV SHARED
        test/mock_shv.cpp
        test/mock_shv.def
    )
    set_target_properties(MockScriptHookV PROPERTIES
        PREFIX ""
        OUTPUT_NAME "ScriptHookV"
    )
    add_executable(mock_host test/mock_host.cpp)
endif()
