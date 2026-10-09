# CMake toolchain file for cross-compiling a 32-bit Windows DLL on macOS
# using the mingw-w64 i686 toolchain from Homebrew.
#
# Homebrew's mingw-w64 ships both i686 and x86_64 cross-compilers. GTA IV is
# 32-bit, so we must build i686. The MinGW install path is architecture-aware
# under /opt/homebrew/opt/mingw-w64 on Apple Silicon.

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR i686)

# Homebrew ships these as `i686-w64-mingw32-{gcc,g++,windres}`.
set(TOOLCHAIN_PREFIX i686-w64-mingw32)

set(CMAKE_C_COMPILER   ${TOOLCHAIN_PREFIX}-gcc)
set(CMAKE_CXX_COMPILER ${TOOLCHAIN_PREFIX}-g++)
set(CMAKE_RC_COMPILER  ${TOOLCHAIN_PREFIX}-windres)

# Static-link libgcc/libstdc++ so the ASI has zero DLL dependencies beyond
# what Windows (and therefore Wine) already provides.
set(CMAKE_EXE_LINKER_FLAGS_INIT    "-static -static-libgcc -static-libstdc++")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
