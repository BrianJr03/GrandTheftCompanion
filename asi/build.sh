#!/usr/bin/env bash
# Build ThorBridge.asi (32-bit Windows DLL) on macOS using mingw-w64 i686.
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v i686-w64-mingw32-g++ >/dev/null 2>&1; then
    echo "error: i686-w64-mingw32-g++ not found. Install with:" >&2
    echo "  brew install mingw-w64" >&2
    exit 1
fi

BUILD_DIR="build"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=../cmake/mingw-i686.cmake \
    -DCMAKE_BUILD_TYPE=Release

cmake --build . --parallel

echo
echo "built: $(pwd)/ThorBridge.asi"
file ThorBridge.asi
