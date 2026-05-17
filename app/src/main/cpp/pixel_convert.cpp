#include "pixel_convert.h"

#include <cstring>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  include <arm_neon.h>
#  define RAW_CAST_HAVE_NEON 1
#else
#  define RAW_CAST_HAVE_NEON 0
#endif

namespace raw_cast {

void rgba_to_bgra(const uint8_t* src, uint8_t* dst, size_t pixel_count) noexcept {
    size_t i = 0;
#if RAW_CAST_HAVE_NEON
    // 16 px per iteration: vld4 / vst4 swap r and b channels.
    for (; i + 16 <= pixel_count; i += 16) {
        uint8x16x4_t v = vld4q_u8(src + i * 4);
        uint8x16_t r = v.val[0];
        v.val[0] = v.val[2];
        v.val[2] = r;
        vst4q_u8(dst + i * 4, v);
    }
#endif
    for (; i < pixel_count; ++i) {
        const uint8_t r = src[i * 4 + 0];
        const uint8_t g = src[i * 4 + 1];
        const uint8_t b = src[i * 4 + 2];
        const uint8_t a = src[i * 4 + 3];
        dst[i * 4 + 0] = b;
        dst[i * 4 + 1] = g;
        dst[i * 4 + 2] = r;
        dst[i * 4 + 3] = a;
    }
}

void rgba_to_bgra_strided(const uint8_t* src, size_t src_stride_bytes,
                          uint8_t* dst,
                          size_t width, size_t height) noexcept {
    for (size_t y = 0; y < height; ++y) {
        rgba_to_bgra(src + y * src_stride_bytes, dst + y * width * 4, width);
    }
}

void rgba_copy_strided(const uint8_t* src, size_t src_stride_bytes,
                       uint8_t* dst,
                       size_t width, size_t height) noexcept {
    const size_t row = width * 4;
    if (src_stride_bytes == row) {
        std::memcpy(dst, src, row * height);
        return;
    }
    for (size_t y = 0; y < height; ++y) {
        std::memcpy(dst + y * row, src + y * src_stride_bytes, row);
    }
}

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
