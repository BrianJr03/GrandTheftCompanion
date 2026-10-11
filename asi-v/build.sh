#!/usr/bin/env bash
# Build ThorBridgeV.asi (64-bit Windows DLL for GTA V) with mingw-w64.
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v x86_64-w64-mingw32-g++ >/dev/null 2>&1; then
    echo "error: x86_64-w64-mingw32-g++ not found. Install with:" >&2
    echo "  macOS:  brew install mingw-w64" >&2
    echo "  Debian: sudo apt-get install g++-mingw-w64-x86-64" >&2
    exit 1
fi

BUILD_DIR="build"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=../cmake/mingw-x86_64.cmake \
    -DCMAKE_BUILD_TYPE=Release \
    "$@"

cmake --build . --parallel

echo
echo "built: $(pwd)/ThorBridgeV.asi"
file ThorBridgeV.asi
