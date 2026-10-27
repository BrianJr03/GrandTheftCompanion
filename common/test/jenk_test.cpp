// Host test for common/jenk.cpp.
//
// jenk is Rockstar's custom LUT-driven filename hash (not Bob Jenkins'
// one-at-a-time hash). The retail LUT lives inside GTA5.exe and is
// located via keys_v::kLutSha1Hex at install time. For off-device
// testing we feed the hash two synthetic LUTs and pin known outputs
// computed by a Python port of GTA5Hash.CalculateHash.
//
//   * Empty-string short-circuit:  input length 0 returns 0.
//   * Identity LUT:                lut[c] = c, so the hash depends
//                                  only on the input bytes. Known
//                                  vectors for "a", "hello.ytd",
//                                  "script.rpf" are pinned.
//   * Rotated LUT:                 lut[c] = (73*c + 11) & 0xff.
//                                  Same inputs give different hashes,
//                                  pinning the LUT dependence.
//   * Null safety:                 null pointers (text or lut) do
//                                  not crash; return 0.

#include "../jenk.h"

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {

int failures = 0;

#define CHECK(cond, msg)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            ++failures;                                                    \
            std::fprintf(stderr, "FAIL: %s (%s:%d)\n", msg, __FILE__,      \
                         __LINE__);                                        \
        }                                                                  \
    } while (0)

void make_identity_lut(std::uint8_t lut[256]) {
    for (int i = 0; i < 256; ++i) lut[i] = static_cast<std::uint8_t>(i);
}

void make_rotated_lut(std::uint8_t lut[256]) {
    for (int i = 0; i < 256; ++i) {
        lut[i] = static_cast<std::uint8_t>((i * 73 + 11) & 0xff);
    }
}

void test_empty_string_short_circuits_to_zero() {
    std::uint8_t lut[256];
    make_identity_lut(lut);
    CHECK(thorbridge::jenk::hash("", lut) == 0u,
          "hash of empty string is 0 (loop never runs)");
    CHECK(thorbridge::jenk::hash(reinterpret_cast<const std::uint8_t*>(""), 0, lut) == 0u,
          "hash with explicit zero length is 0");
}

void test_identity_lut_known_vectors() {
    std::uint8_t lut[256];
    make_identity_lut(lut);
    CHECK(thorbridge::jenk::hash("a", lut) == 0xCA2E9442u,
          "hash of 'a' under identity LUT matches Python reference");
    CHECK(thorbridge::jenk::hash("hello.ytd", lut) == 0xFDF5E291u,
          "hash of 'hello.ytd' under identity LUT matches Python reference");
    CHECK(thorbridge::jenk::hash("script.rpf", lut) == 0x46DDC648u,
          "hash of 'script.rpf' under identity LUT matches Python reference");
}

void test_rotated_lut_known_vectors() {
    std::uint8_t lut[256];
    make_rotated_lut(lut);
    CHECK(thorbridge::jenk::hash("a", lut) == 0xDC623891u,
          "hash of 'a' under rotated LUT matches Python reference");
    CHECK(thorbridge::jenk::hash("script.rpf", lut) == 0x7EC0703Fu,
          "hash of 'script.rpf' under rotated LUT matches Python reference");
}

void test_hash_depends_on_lut() {
    std::uint8_t id_lut[256], rot_lut[256];
    make_identity_lut(id_lut);
    make_rotated_lut(rot_lut);
    CHECK(thorbridge::jenk::hash("script.rpf", id_lut) !=
          thorbridge::jenk::hash("script.rpf", rot_lut),
          "same input under different LUTs produces different hashes");
}

void test_explicit_length_handles_embedded_nulls() {
    std::uint8_t lut[256];
    make_identity_lut(lut);
    const std::uint8_t bytes[] = {'a', 0x00, 'b'};
    std::uint32_t h3 = thorbridge::jenk::hash(bytes, 3, lut);
    std::uint32_t h1 = thorbridge::jenk::hash(bytes, 1, lut);
    CHECK(h3 != h1,
          "explicit length folds past embedded nulls; strlen would truncate");
}

void test_null_safety() {
    std::uint8_t lut[256];
    make_identity_lut(lut);
    CHECK(thorbridge::jenk::hash(static_cast<const char*>(nullptr), lut) == 0u,
          "null C-string input returns 0");
    CHECK(thorbridge::jenk::hash(static_cast<const std::uint8_t*>(nullptr), 5, lut) == 0u,
          "null byte-span input returns 0");
    CHECK(thorbridge::jenk::hash("a", nullptr) == 0u,
          "null LUT returns 0");
}

}  // namespace

int main() {
    test_empty_string_short_circuits_to_zero();
    test_identity_lut_known_vectors();
    test_rotated_lut_known_vectors();
    test_hash_depends_on_lut();
    test_explicit_length_handles_embedded_nulls();
    test_null_safety();
    if (failures) {
        std::fprintf(stderr, "\n%d jenk check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all jenk checks passed.\n");
    return 0;
}
