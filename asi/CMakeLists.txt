cmake_minimum_required(VERSION 3.20)
project(ThorBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(ThorBridge SHARED
    src/bridge.cpp
    src/dllmain.cpp
    src/hook.cpp
    src/log.cpp
    src/natives.cpp
    src/patterns.cpp
    src/probes.cpp
    src/snapshot.cpp
    src/version_detect.cpp
)

target_compile_options(ThorBridge PRIVATE
    -Wall -Wextra -Wpedantic
    -fno-exceptions
)

target_link_libraries(ThorBridge PRIVATE
    version   # GetFileVersionInfoA / VerQueryValueA
    ws2_32    # winsock, needed from M3 onward
)

set_target_properties(ThorBridge PROPERTIES
    PREFIX ""
    SUFFIX ".asi"
    OUTPUT_NAME "ThorBridge"
)
