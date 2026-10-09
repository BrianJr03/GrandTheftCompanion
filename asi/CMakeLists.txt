cmake_minimum_required(VERSION 3.20)
project(ThorBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(ThorBridge SHARED
    src/dllmain.cpp
)

target_compile_options(ThorBridge PRIVATE
    -Wall -Wextra -Wpedantic
    -fno-exceptions
)

target_link_libraries(ThorBridge PRIVATE
    ws2_32   # winsock, used from M3 onward
)

# Produce ThorBridge.asi instead of libThorBridge.dll
set_target_properties(ThorBridge PROPERTIES
    PREFIX ""
    SUFFIX ".asi"
    OUTPUT_NAME "ThorBridge"
)
