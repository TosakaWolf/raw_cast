#pragma once
#include <cstddef>
#include <cstdint>

namespace raw_cast {

void rgb565_copy_strided(const uint8_t* src, size_t src_stride_bytes,
                         uint8_t* dst,
                         size_t width, size_t height) noexcept;

} // namespace raw_cast
