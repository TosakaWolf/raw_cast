#include "pixel_convert.h"

#include <cstring>

namespace raw_cast {

void rgb565_copy_strided(const uint8_t* src, size_t src_stride_bytes,
                         uint8_t* dst,
                         size_t width, size_t height) noexcept {
    const size_t row = width * 2;
    if (src_stride_bytes == row) {
        std::memcpy(dst, src, row * height);
        return;
    }
    for (size_t y = 0; y < height; ++y) {
        std::memcpy(dst + y * row, src + y * src_stride_bytes, row);
    }
}

} // namespace raw_cast
