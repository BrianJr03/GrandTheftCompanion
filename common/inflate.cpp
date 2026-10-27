#include "inflate.h"

#include <cstring>

namespace thorbridge::inflate {

namespace {

// ---------- bit reader --------------------------------------------------
//
// DEFLATE packs bits LSB-first within each byte. The reader shifts bytes
// into a 64-bit buffer and exposes read(n) and align(). On underflow the
// reader keeps returning 0 but flips `error`, so callers can defer the
// check to a single place.

struct Bits {
    const uint8_t* p;
    std::size_t    left;
    uint64_t       buf;
    int            held;
    bool           error;

    Bits(const uint8_t* src, std::size_t n)
        : p(src), left(n), buf(0), held(0), error(false) {}

    void fill(int bits_needed) {
        while (held < bits_needed) {
            if (left == 0) { error = true; return; }
            buf |= static_cast<uint64_t>(*p++) << held;
            --left;
            held += 8;
        }
    }

    uint32_t read(int n) {
        fill(n);
        if (error) return 0;
        uint32_t v = static_cast<uint32_t>(buf & ((1ull << n) - 1u));
        buf >>= n;
        held -= n;
        return v;
    }

    // Align the reader to the next byte boundary without consuming bits
    // past the current byte. Used by stored blocks.
    void align_byte() {
        int drop = held & 7;
        buf >>= drop;
        held -= drop;
    }

    bool read_bytes(uint8_t* dst, std::size_t n) {
        if (left < n) { error = true; return false; }
        std::memcpy(dst, p, n);
        p += n;
        left -= n;
        return true;
    }
};

// ---------- Huffman decoding -------------------------------------------
//
// A compact canonical-Huffman decoder: build a count-of-each-length
// histogram and a lookup table of symbols sorted by (length, symbol).
// Decode by walking the table; the alphabets stay small enough that the
// straightforward walk performs fine for the atlas use.

struct Huffman {
    // counts[i] = number of codes of length i (1..15)
    int   counts[16];
    // symbols sorted by (length, symbol) — total length = sum(counts)
    uint16_t symbols[288];  // 288 = max literal/length alphabet size
};

bool build_huffman(Huffman& h, const uint8_t* lengths, int n) {
    for (int i = 0; i < 16; ++i) h.counts[i] = 0;
    for (int i = 0; i < n; ++i) {
        int len = lengths[i];
        if (len > 15) return false;
        if (len > 0) ++h.counts[len];
    }
    // Place each symbol into h.symbols at its sorted offset.
    int offs[16];
    offs[0] = 0;
    for (int i = 1; i < 16; ++i) offs[i] = offs[i - 1] + h.counts[i - 1];
    for (int i = 0; i < n; ++i) {
        int len = lengths[i];
        if (len > 0) {
            h.symbols[offs[len]++] = static_cast<uint16_t>(i);
        }
    }
    return true;
}

// Return next symbol from `bits` using `h`. Error flag set on failure.
int decode_symbol(Bits& bits, const Huffman& h) {
    int code = 0;
    int first = 0;
    int index = 0;
    for (int len = 1; len <= 15; ++len) {
        code |= static_cast<int>(bits.read(1));
        if (bits.error) return -1;
        int count = h.counts[len];
        if (code - count < first) {
            return h.symbols[index + (code - first)];
        }
        index += count;
        first = (first + count) << 1;
        code <<= 1;
    }
    bits.error = true;
    return -1;
}

// ---------- fixed Huffman tables ---------------------------------------

void build_fixed(Huffman& litlen, Huffman& dist) {
    uint8_t ll[288];
    for (int i = 0;   i <= 143; ++i) ll[i] = 8;
    for (int i = 144; i <= 255; ++i) ll[i] = 9;
    for (int i = 256; i <= 279; ++i) ll[i] = 7;
    for (int i = 280; i <= 287; ++i) ll[i] = 8;
    build_huffman(litlen, ll, 288);
    uint8_t d[30];
    for (int i = 0; i < 30; ++i) d[i] = 5;
    build_huffman(dist, d, 30);
}

// Length-code base values + extra bits (symbols 257..285).
const uint16_t kLenBase[29] = {
      3,   4,   5,   6,   7,   8,   9,  10,  11,  13,
     15,  17,  19,  23,  27,  31,  35,  43,  51,  59,
     67,  83,  99, 115, 131, 163, 195, 227, 258
};
const uint8_t kLenExtra[29] = {
    0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0
};
// Distance-code base values + extra bits (symbols 0..29).
const uint16_t kDistBase[30] = {
       1,    2,    3,    4,    5,    7,    9,   13,   17,   25,
      33,   49,   65,   97,  129,  193,  257,  385,  513,  769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193,12289,16385,24577
};
const uint8_t kDistExtra[30] = {
    0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13
};
// Code-length alphabet order (RFC 1951 §3.2.7).
const uint8_t kClOrder[19] = {
    16,17,18, 0, 8, 7, 9, 6,10, 5,11, 4,12, 3,13, 2,14, 1,15
};

// Inflate the body of a single Huffman-coded block (fixed or dynamic).
bool inflate_block(Bits& bits, const Huffman& ll, const Huffman& dist,
                   std::vector<uint8_t>& out) {
    while (true) {
        int sym = decode_symbol(bits, ll);
        if (sym < 0) return false;
        if (sym < 256) {
            out.push_back(static_cast<uint8_t>(sym));
        } else if (sym == 256) {
            return true;
        } else {
            int li = sym - 257;
            if (li >= 29) return false;
            int len = kLenBase[li] + static_cast<int>(bits.read(kLenExtra[li]));
            if (bits.error) return false;
            int dsym = decode_symbol(bits, dist);
            if (dsym < 0 || dsym >= 30) return false;
            int d = kDistBase[dsym] +
                    static_cast<int>(bits.read(kDistExtra[dsym]));
            if (bits.error) return false;
            if (d <= 0 || static_cast<std::size_t>(d) > out.size()) return false;
            std::size_t copy_from = out.size() - static_cast<std::size_t>(d);
            for (int i = 0; i < len; ++i) {
                out.push_back(out[copy_from + i]);
            }
        }
    }
}

bool read_dynamic_tables(Bits& bits, Huffman& ll, Huffman& dist) {
    int hlit  = static_cast<int>(bits.read(5)) + 257;
    int hdist = static_cast<int>(bits.read(5)) + 1;
    int hclen = static_cast<int>(bits.read(4)) + 4;
    if (hlit > 286 || hdist > 30 || hclen > 19) return false;

    uint8_t cl_lengths[19] = {0};
    for (int i = 0; i < hclen; ++i) {
        cl_lengths[kClOrder[i]] = static_cast<uint8_t>(bits.read(3));
    }
    if (bits.error) return false;
    Huffman cl;
    if (!build_huffman(cl, cl_lengths, 19)) return false;

    uint8_t lengths[286 + 30];
    int total = hlit + hdist;
    int i = 0;
    while (i < total) {
        int sym = decode_symbol(bits, cl);
        if (sym < 0) return false;
        if (sym < 16) {
            lengths[i++] = static_cast<uint8_t>(sym);
        } else if (sym == 16) {
            if (i == 0) return false;
            int rep = static_cast<int>(bits.read(2)) + 3;
            uint8_t v = lengths[i - 1];
            while (rep-- && i < total) lengths[i++] = v;
        } else if (sym == 17) {
            int rep = static_cast<int>(bits.read(3)) + 3;
            while (rep-- && i < total) lengths[i++] = 0;
        } else if (sym == 18) {
            int rep = static_cast<int>(bits.read(7)) + 11;
            while (rep-- && i < total) lengths[i++] = 0;
        } else {
            return false;
        }
    }
    if (bits.error) return false;
    return build_huffman(ll, lengths, hlit) &&
           build_huffman(dist, lengths + hlit, hdist);
}

uint32_t adler32(const uint8_t* data, std::size_t n) {
    uint32_t a = 1, b = 0;
    for (std::size_t i = 0; i < n; ++i) {
        a = (a + data[i]) % 65521u;
        b = (b + a) % 65521u;
    }
    return (b << 16) | a;
}

}  // namespace

bool decompress(const uint8_t* src, std::size_t src_bytes,
                std::vector<uint8_t>& out,
                bool expect_zlib,
                bool check_adler) {
    std::size_t out_start = out.size();
    const uint8_t* defl = src;
    std::size_t defl_bytes = src_bytes;
    if (expect_zlib) {
        if (src_bytes < 2) return false;
        // CMF low 4 bits == 8 → deflate, upper 4 == log2(window) - 8.
        uint8_t cmf = src[0], flg = src[1];
        if ((cmf & 0x0F) != 8) return false;
        if ((static_cast<unsigned>(cmf) * 256u + flg) % 31u != 0) return false;
        if (flg & 0x20) return false;  // preset dictionary not supported
        defl = src + 2;
        // When the caller promises an Adler-32 at the tail, carve off
        // the last 4 bytes so the DEFLATE walker can't overshoot into
        // them. When they don't, the DEFLATE walker still stops cleanly
        // at the BFINAL=1 block header so a longer buffer is fine.
        if (check_adler) {
            if (src_bytes < 6) return false;
            defl_bytes = src_bytes - 6;
        } else {
            defl_bytes = src_bytes - 2;
        }
    }

    Bits bits(defl, defl_bytes);
    while (true) {
        uint32_t bfinal = bits.read(1);
        uint32_t btype  = bits.read(2);
        if (bits.error) return false;
        if (btype == 0) {
            bits.align_byte();
            // LEN / NLEN as 2 bytes each, little-endian.
            uint8_t header[4];
            if (!bits.read_bytes(header, 4)) return false;
            uint16_t len  = static_cast<uint16_t>(header[0] | (header[1] << 8));
            uint16_t nlen = static_cast<uint16_t>(header[2] | (header[3] << 8));
            if (static_cast<uint16_t>(~len) != nlen) return false;
            std::vector<uint8_t> tmp(len);
            if (len && !bits.read_bytes(tmp.data(), len)) return false;
            out.insert(out.end(), tmp.begin(), tmp.end());
        } else if (btype == 1) {
            Huffman ll, dist;
            build_fixed(ll, dist);
            if (!inflate_block(bits, ll, dist, out)) return false;
        } else if (btype == 2) {
            Huffman ll, dist;
            if (!read_dynamic_tables(bits, ll, dist)) return false;
            if (!inflate_block(bits, ll, dist, out)) return false;
        } else {
            return false;  // reserved
        }
        if (bfinal) break;
    }

    if (expect_zlib && check_adler) {
        // After the final block the reader is byte-aligned; the Adler
        // trailer is the last 4 bytes of src.
        if (src_bytes < 4) return false;
        const uint8_t* adp = src + src_bytes - 4;
        uint32_t decl = (static_cast<uint32_t>(adp[0]) << 24) |
                        (static_cast<uint32_t>(adp[1]) << 16) |
                        (static_cast<uint32_t>(adp[2]) <<  8) |
                         static_cast<uint32_t>(adp[3]);
        uint32_t calc = adler32(out.data() + out_start,
                                out.size() - out_start);
        if (calc != decl) return false;
    }
    return true;
}

}  // namespace thorbridge::inflate
