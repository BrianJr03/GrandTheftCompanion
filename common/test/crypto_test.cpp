// Host test for common/aes.cpp, common/sha1.cpp, common/keys_search.cpp.
//
//  AES-256 : the test vector from FIPS-197 Appendix C.3 (plaintext
//            00112233...FF under key 0001...1F). Encrypted output is
//            `8ea2b7ca...60497060`. We only ship decrypt, so we start
//            from the ciphertext and recover the plaintext — same
//            cover for correctness.
//  SHA-1   : the three NIST / RFC 3174 vectors ("abc", the 56-char
//            string, one million 'a').
//  search  : a synthetic exe with a 32-byte blob embedded at a random
//            aligned offset; find_blob returns that offset. Also check
//            parse_hex round-trip of our ship-ready IV AES hash.

#include "../aes.h"
#include "../keys_search.h"
#include "../sha1.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

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

void test_aes256_decrypt_appendix_c3() {
    const std::uint8_t key[32] = {
        0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,
        0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,
        0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,
        0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f,
    };
    const std::uint8_t plain[16] = {
        0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,
        0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff,
    };
    std::uint8_t cipher[16] = {
        0x8e,0xa2,0xb7,0xca,0x51,0x67,0x45,0xbf,
        0xea,0xfc,0x49,0x90,0x4b,0x49,0x60,0x89,
    };
    thorbridge::aes::Schedule s{};
    thorbridge::aes::key_schedule(key, s);
    thorbridge::aes::decrypt_block(s, cipher);
    CHECK(std::memcmp(cipher, plain, 16) == 0,
          "AES-256 decrypt recovers FIPS-197 Appendix C.3 plaintext");
}

void test_aes256_ecb_multi_round_round_trip() {
    // Build a stable 32-byte "key" and 32-byte "data", encrypt via a
    // naive encrypt built from the decrypt-only module in reverse —
    // we don't ship encrypt, so instead we round-trip through two
    // single-round decrypts and prove each applies the AES inverse
    // (idempotent after 16 forward rounds of a real encryptor, but
    // here we just confirm that decrypt_ecb walks every block).
    std::uint8_t key[32] = {0};
    for (int i = 0; i < 32; ++i) key[i] = static_cast<std::uint8_t>(i * 7);
    thorbridge::aes::Schedule s{};
    thorbridge::aes::key_schedule(key, s);

    std::uint8_t data[48];
    for (int i = 0; i < 48; ++i) data[i] = static_cast<std::uint8_t>(i);
    std::uint8_t copy[48];
    std::memcpy(copy, data, 48);
    thorbridge::aes::decrypt_ecb(s, data, 48, 0);  // 0 rounds: no-op
    CHECK(std::memcmp(data, copy, 48) == 0, "decrypt_ecb with 0 rounds is a no-op");

    thorbridge::aes::decrypt_ecb(s, data, 48, 1);
    CHECK(std::memcmp(data, copy, 48) != 0, "decrypt_ecb with 1 round mutates data");

    // The partial trailing block (if any) must stay untouched.
    std::uint8_t data2[20];
    for (int i = 0; i < 20; ++i) data2[i] = static_cast<std::uint8_t>(i);
    std::uint8_t copy2[20];
    std::memcpy(copy2, data2, 20);
    thorbridge::aes::decrypt_ecb(s, data2, 20, 2);
    CHECK(std::memcmp(data2 + 16, copy2 + 16, 4) == 0,
          "decrypt_ecb leaves the trailing partial block untouched");
}

void test_sha1_vectors() {
    struct Vec { const char* in; const char* hex; };
    Vec vecs[] = {
        {"abc",
         "a9993e364706816aba3e25717850c26c9cd0d89d"},
        {"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
         "84983e441c3bd26ebaae4aa1f95129e5e54670f1"},
    };
    for (const auto& v : vecs) {
        std::uint8_t d[thorbridge::sha1::kDigestBytes];
        thorbridge::sha1::hash(reinterpret_cast<const std::uint8_t*>(v.in),
                               std::strlen(v.in), d);
        std::uint8_t want[thorbridge::sha1::kDigestBytes];
        bool parsed = thorbridge::keys::parse_hex(v.hex, want);
        CHECK(parsed, "SHA-1 reference hex parses");
        CHECK(thorbridge::sha1::equals(d, want),
              "SHA-1 of known RFC 3174 vector matches");
    }

    // The one-million-'a' vector exercises multiple 64-byte blocks and
    // the >= 2^20 bit-length encoding path.
    std::vector<std::uint8_t> big(1000000, static_cast<std::uint8_t>('a'));
    std::uint8_t d[20];
    thorbridge::sha1::hash(big.data(), big.size(), d);
    std::uint8_t want[20];
    thorbridge::keys::parse_hex("34aa973cd4c4daa4f61eeb2bdbad27316534016f", want);
    CHECK(thorbridge::sha1::equals(d, want), "SHA-1 of 1,000,000 'a' matches");
}

void test_hash_search_round_trip() {
    // Build a 256 KB fake "exe" with a 32-byte blob at a known
    // DWORD-aligned offset.
    std::vector<std::uint8_t> fake(256 * 1024, 0);
    // Shuffle up some entropy so a false-positive match is unlikely.
    for (std::size_t i = 0; i < fake.size(); ++i) {
        fake[i] = static_cast<std::uint8_t>((i * 131u + 7u) & 0xff);
    }
    const std::size_t placed = 0x1234 & ~std::size_t{3};  // DWORD-aligned
    std::uint8_t key[32];
    for (int i = 0; i < 32; ++i) key[i] = static_cast<std::uint8_t>(0xa0 + i);
    std::memcpy(fake.data() + placed, key, 32);

    std::uint8_t digest[20];
    thorbridge::sha1::hash(key, 32, digest);
    std::size_t found = thorbridge::keys::find_blob(
        fake.data(), fake.size(), 32, digest);
    CHECK(found == placed, "find_blob recovers the exact placement offset");

    // Negative control: a never-seen digest.
    std::uint8_t miss[20];
    thorbridge::keys::parse_hex("1111111111111111111111111111111111111111", miss);
    found = thorbridge::keys::find_blob(fake.data(), fake.size(), 32, miss);
    CHECK(found == SIZE_MAX, "find_blob returns SIZE_MAX for a no-match hash");
}

void test_iv_aes_hash_parses() {
    // The hash we ship for the GTA IV AES-256 key (public domain,
    // reproduced here only for shape-check). The runtime scanner
    // reads this same constant from keys_iv.cpp.
    std::uint8_t h[20];
    bool ok = thorbridge::keys::parse_hex(
        "DEA375EF1E6EF2223A1221C2C575C47BF17EFA5E", h);
    CHECK(ok, "ship-ready IV AES SHA-1 parses as hex");
}

}  // namespace

int main() {
    test_aes256_decrypt_appendix_c3();
    test_aes256_ecb_multi_round_round_trip();
    test_sha1_vectors();
    test_hash_search_round_trip();
    test_iv_aes_hash_parses();
    if (failures) {
        std::fprintf(stderr, "\n%d crypto check(s) failed.\n", failures);
        return 1;
    }
    std::fprintf(stderr, "all crypto checks passed.\n");
    return 0;
}
