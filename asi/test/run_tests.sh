#!/usr/bin/env bash
# Build and run the host-side tests for the GTA IV plugin's weapon-wheel
# and blip-census logic. Needs only a C++17 compiler for this machine ($CXX, else c++).
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p build
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    test/weapons_test.cpp src/weapons.cpp \
    -o build/weapons_test
./build/weapons_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    test/blips_test.cpp src/blips.cpp \
    -o build/blips_test
./build/blips_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    test/cheats_test.cpp src/cheats.cpp src/weapons.cpp \
    -o build/cheats_test
./build/cheats_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/dxt_png_test.cpp ../common/dxt.cpp ../common/png.cpp \
    -o build/dxt_png_test
./build/dxt_png_test
