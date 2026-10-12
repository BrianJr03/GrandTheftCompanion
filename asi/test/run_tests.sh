#!/usr/bin/env bash
# Build and run the host-side tests for the GTA IV plugin's weapon-wheel
# logic. Needs only a C++17 compiler for this machine ($CXX, else c++).
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p build
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    test/weapons_test.cpp src/weapons.cpp \
    -o build/weapons_test
./build/weapons_test
