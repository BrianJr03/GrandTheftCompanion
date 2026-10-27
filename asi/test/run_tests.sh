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
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/inflate_test.cpp ../common/inflate.cpp \
    -o build/inflate_test
./build/inflate_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/img_test.cpp ../common/img.cpp \
    -o build/img_test
./build/img_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/crypto_test.cpp ../common/aes.cpp ../common/sha1.cpp \
    ../common/keys_search.cpp \
    -o build/crypto_test
./build/crypto_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/img_v3_test.cpp ../common/aes.cpp ../common/img_v3.cpp \
    -o build/img_v3_test
./build/img_v3_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/ng_test.cpp ../common/ng.cpp \
    -o build/ng_test
./build/ng_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/jenk_test.cpp ../common/jenk.cpp \
    -o build/jenk_test
./build/jenk_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/keys_v_test.cpp ../common/keys_v.cpp \
    ../common/keys_search.cpp ../common/sha1.cpp \
    -o build/keys_v_test
./build/keys_v_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/rpf7_test.cpp ../common/rpf7.cpp \
    ../common/aes.cpp ../common/jenk.cpp ../common/ng.cpp \
    -o build/rpf7_test
./build/rpf7_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/rsc7_test.cpp ../common/rsc7.cpp ../common/inflate.cpp \
    -o build/rsc7_test
./build/rsc7_test
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -O1 \
    ../common/test/ytd_test.cpp ../common/ytd.cpp \
    -o build/ytd_test
./build/ytd_test
