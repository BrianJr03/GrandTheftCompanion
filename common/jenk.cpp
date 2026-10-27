#include "jenk.h"

#include <cstring>

namespace thorbridge::jenk {

std::uint32_t hash(const std::uint8_t* text, std::size_t len,
                   const std::uint8_t lut[kLutBytes]) {
    if (!text || !lut) return 0;
    std::uint32_t result = 0;
    for (std::size_t i = 0; i < len; ++i) {
        std::uint32_t temp =
            1025u * (static_cast<std::uint32_t>(lut[text[i]]) + result);
        result = (temp >> 6) ^ temp;
    }
    std::uint32_t nine_r = 9u * result;
    return 32769u * ((nine_r >> 11) ^ nine_r);
}

std::uint32_t hash(const char* text, const std::uint8_t lut[kLutBytes]) {
    if (!text) return 0;
    return hash(reinterpret_cast<const std::uint8_t*>(text),
                std::strlen(text), lut);
}

}  // namespace thorbridge::jenk
