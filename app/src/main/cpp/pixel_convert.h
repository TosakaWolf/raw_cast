#pragma once
#include <cstddef>
#include <cstdint>

namespace raw_cast {

// All converters assume tightly-packed source rows (stride == width*bpp).
// Destination is also tightly packed.

// In-place would alias; these are out-of-place.
void rgba_to_bgra(const uint8_t* src, uint8_t* dst, size_t pixel_count) noexcept;

// Stride-aware variants used when the GPU stride > width.
void rgba_to_bgra_strided(const uint8_t* src, size_t src_stride_bytes,
                          uint8_t* dst,
                          size_t width, size_t height) noexcept;
void rgba_copy_strided(const uint8_t* src, size_t src_stride_bytes,
                       uint8_t* dst,
                       size_t width, size_t height) noexcept;
void rgb565_copy_strided(const uint8_t* src, size_t src_stride_bytes,
                         uint8_t* dst,
                         size_t width, size_t height) noexcept;

} // namespace raw_cast
