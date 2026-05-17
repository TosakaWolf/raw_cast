// raw_cast JNI bridge.
//
// Performance-critical path: locks an AHardwareBuffer / AndroidBitmap, performs
// an in-place pixel-format conversion (when needed) directly into a caller
// supplied direct ByteBuffer, optionally followed by an LZ4 frame. Avoids the
// extra Bitmap.copy(...) round-trip used by the reference DroidCast_raw.

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>

#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <lz4.h>

#include "pixel_convert.h"

#define TAG "raw_cast_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

#define CHECKED(ret, cond, ...) do { if (!(cond)) { LOGE(__VA_ARGS__); return (ret); } } while (0)

// Output formats: must mirror PixelFmt.id in Kotlin.
namespace fmt {
constexpr int RAW_RGB565   = 1;
constexpr int RAW_RGBA8888 = 2;
constexpr int RAW_BGRA8888 = 3;
}

static inline size_t bpp_for(int format) {
    switch (format) {
        case fmt::RAW_RGB565:   return 2;
        case fmt::RAW_RGBA8888: return 4;
        case fmt::RAW_BGRA8888: return 4;
        default:                return 0;
    }
}

struct ThreadScratch {
    uint8_t* data = nullptr;
    size_t capacity = 0;

    ~ThreadScratch() {
        std::free(data);
    }

    bool ensure(size_t size) {
        if (capacity >= size) return true;
        void* next = std::realloc(data, size);
        if (!next) return false;
        data = static_cast<uint8_t*>(next);
        capacity = size;
        return true;
    }
};

static thread_local ThreadScratch g_scratch;

// Convert an RGBA_8888 source plane (with given byte stride) into the requested
// destination format, packed tightly into `dst`.
static int convert_from_rgba8888(const uint8_t* src, size_t src_stride_bytes,
                                 uint8_t* dst, size_t dst_capacity,
                                 uint32_t width, uint32_t height,
                                 int target_format) {
    const size_t pixel_count = static_cast<size_t>(width) * height;
    const size_t need = pixel_count * bpp_for(target_format);
    CHECKED(-1, need > 0 && need <= dst_capacity,
            "dst buffer too small: need=%zu cap=%zu", need, dst_capacity);
    switch (target_format) {
        case fmt::RAW_RGBA8888:
            raw_cast::rgba_copy_strided(src, src_stride_bytes, dst, width, height);
            return static_cast<int>(need);
        case fmt::RAW_BGRA8888:
            raw_cast::rgba_to_bgra_strided(src, src_stride_bytes, dst, width, height);
            return static_cast<int>(need);
        default:
            LOGE("source RGBA8888 cannot be converted to format=%d", target_format);
            return -1;
    }
}

// Convert an RGB_565 source plane to either packed RGB565 (memcpy) or to one of
// the 8888 formats (cheap CPU expansion via lookup-free shift).
static int convert_from_rgb565(const uint8_t* src, size_t src_stride_bytes,
                               uint8_t* dst, size_t dst_capacity,
                               uint32_t width, uint32_t height,
                               int target_format) {
    const size_t pixel_count = static_cast<size_t>(width) * height;
    const size_t need = pixel_count * bpp_for(target_format);
    CHECKED(-1, need > 0 && need <= dst_capacity,
            "dst buffer too small: need=%zu cap=%zu", need, dst_capacity);
    if (target_format == fmt::RAW_RGB565) {
        raw_cast::rgb565_copy_strided(src, src_stride_bytes, dst, width, height);
        return static_cast<int>(need);
    }
    // CPU expansion path. Slow-ish but correct; only used if the GPU yields 565
    // but the user requested an 8888 variant.
    const size_t row_pixels = width;
    for (size_t y = 0; y < height; ++y) {
        const uint16_t* row = reinterpret_cast<const uint16_t*>(src + y * src_stride_bytes);
        for (size_t x = 0; x < row_pixels; ++x) {
            const uint16_t v = row[x];
            const uint8_t r = static_cast<uint8_t>(((v >> 11) & 0x1F) * 255 / 31);
            const uint8_t g = static_cast<uint8_t>(((v >> 5)  & 0x3F) * 255 / 63);
            const uint8_t b = static_cast<uint8_t>(((v >> 0)  & 0x1F) * 255 / 31);
            uint8_t* px;
            switch (target_format) {
                case fmt::RAW_RGBA8888:
                    px = dst + (y * row_pixels + x) * 4;
                    px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
                    break;
                case fmt::RAW_BGRA8888:
                    px = dst + (y * row_pixels + x) * 4;
                    px[0] = b; px[1] = g; px[2] = r; px[3] = 0xFF;
                    break;
                default:
                    return -1;
            }
        }
    }
    return static_cast<int>(need);
}

// ---------------------------------------------------------------------------
// JNI entry points
//
// All functions return >= 0 on success (number of bytes written into the
// destination ByteBuffer, starting at `position`) or -1 on failure. The Kotlin
// caller is responsible for advancing the buffer position.
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
Java_ink_mol_raw_1cast_NativeBridge_copyHardwareBuffer(
        JNIEnv* env, jclass /*clazz*/,
        jobject hardware_buffer, jobject byte_buffer,
        jint position, jint limit, jint target_format, jboolean lz4_compress) {

    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hardware_buffer);
    CHECKED(-1, hb, "invalid hardware buffer");

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hb, &desc);

    void* dst = env->GetDirectBufferAddress(byte_buffer);
    CHECKED(-1, dst, "failed to get direct byte buffer ptr");
    uint8_t* dst_ptr = static_cast<uint8_t*>(dst) + position;
    const size_t dst_capacity = static_cast<size_t>(limit - position);

    void* src_ptr = nullptr;
    int lock = AHardwareBuffer_lock(hb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &src_ptr);
    CHECKED(-1, lock == 0, "failed to lock hw buffer (rc=%d)", lock);

    int written = -1;

    // The stride reported by AHardwareBuffer_Desc is in PIXELS for 4/2-byte
    // formats. Convert to bytes using the source bytes-per-pixel.
    size_t src_bpp = 0;
    bool source_is_rgba = false, source_is_565 = false;
    switch (desc.format) {
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
            src_bpp = 4; source_is_rgba = true; break;
        case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
            src_bpp = 2; source_is_565 = true; break;
        default:
            LOGE("unsupported hw buffer format: %" PRIu32, desc.format);
            AHardwareBuffer_unlock(hb, nullptr);
            return -1;
    }
    const size_t src_stride_bytes = static_cast<size_t>(desc.stride) * src_bpp;

    // Fast path: source already matches target and no compression needed.
    const bool need_compress = (lz4_compress == JNI_TRUE);

    if (!need_compress) {
        if (source_is_rgba) {
            written = convert_from_rgba8888(static_cast<const uint8_t*>(src_ptr),
                                            src_stride_bytes,
                                            dst_ptr, dst_capacity,
                                            desc.width, desc.height, target_format);
        } else if (source_is_565) {
            written = convert_from_rgb565(static_cast<const uint8_t*>(src_ptr),
                                          src_stride_bytes,
                                          dst_ptr, dst_capacity,
                                          desc.width, desc.height, target_format);
        }
    } else {
        // Compression path: convert into a small heap scratch buffer, then LZ4
        // straight into the output ByteBuffer.
        const size_t pixel_count = static_cast<size_t>(desc.width) * desc.height;
        const size_t raw_size    = pixel_count * bpp_for(target_format);
        if (raw_size == 0) {
            AHardwareBuffer_unlock(hb, nullptr);
            return -1;
        }
        if (!g_scratch.ensure(raw_size)) {
            AHardwareBuffer_unlock(hb, nullptr);
            LOGE("oom alloc scratch %zu", raw_size);
            return -1;
        }
        uint8_t* scratch = g_scratch.data;
        int raw_written = source_is_rgba
                ? convert_from_rgba8888(static_cast<const uint8_t*>(src_ptr), src_stride_bytes,
                                        scratch, raw_size, desc.width, desc.height, target_format)
                : convert_from_rgb565(static_cast<const uint8_t*>(src_ptr), src_stride_bytes,
                                      scratch, raw_size, desc.width, desc.height, target_format);
        if (raw_written < 0) {
            AHardwareBuffer_unlock(hb, nullptr);
            return -1;
        }
        const int compressed = LZ4_compress_default(reinterpret_cast<const char*>(scratch),
                                                    reinterpret_cast<char*>(dst_ptr),
                                                    raw_written,
                                                    static_cast<int>(dst_capacity));
        if (compressed <= 0) {
            LOGE("LZ4_compress_default failed (raw=%d, cap=%zu)", raw_written, dst_capacity);
            written = -1;
        } else {
            written = compressed;
        }
    }

    AHardwareBuffer_unlock(hb, nullptr);
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_ink_mol_raw_1cast_NativeBridge_copyBitmap(
        JNIEnv* env, jclass /*clazz*/,
        jobject bitmap, jobject byte_buffer,
        jint position, jint limit, jint target_format, jboolean lz4_compress) {

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return -1;
    }

    void* dst = env->GetDirectBufferAddress(byte_buffer);
    CHECKED(-1, dst, "failed to get direct byte buffer ptr");
    uint8_t* dst_ptr = static_cast<uint8_t*>(dst) + position;
    const size_t dst_capacity = static_cast<size_t>(limit - position);

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed");
        return -1;
    }

    bool source_is_rgba = false, source_is_565 = false;
    switch (info.format) {
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            source_is_rgba = true; break;
        case ANDROID_BITMAP_FORMAT_RGB_565:
            source_is_565 = true; break;
        default:
            AndroidBitmap_unlockPixels(env, bitmap);
            LOGE("unsupported bitmap format: %u", info.format);
            return -1;
    }

    const bool need_compress = (lz4_compress == JNI_TRUE);
    int written = -1;

    if (!need_compress) {
        written = source_is_rgba
                ? convert_from_rgba8888(static_cast<const uint8_t*>(pixels), info.stride,
                                        dst_ptr, dst_capacity, info.width, info.height, target_format)
                : convert_from_rgb565(static_cast<const uint8_t*>(pixels), info.stride,
                                      dst_ptr, dst_capacity, info.width, info.height, target_format);
    } else {
        const size_t pixel_count = static_cast<size_t>(info.width) * info.height;
        const size_t raw_size    = pixel_count * bpp_for(target_format);
        if (raw_size == 0) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return -1;
        }
        if (!g_scratch.ensure(raw_size)) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return -1;
        }
        uint8_t* scratch = g_scratch.data;
        int raw_written = source_is_rgba
                ? convert_from_rgba8888(static_cast<const uint8_t*>(pixels), info.stride,
                                        scratch, raw_size, info.width, info.height, target_format)
                : convert_from_rgb565(static_cast<const uint8_t*>(pixels), info.stride,
                                      scratch, raw_size, info.width, info.height, target_format);
        if (raw_written < 0) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return -1;
        }
        const int compressed = LZ4_compress_default(reinterpret_cast<const char*>(scratch),
                                                    reinterpret_cast<char*>(dst_ptr),
                                                    raw_written,
                                                    static_cast<int>(dst_capacity));
        written = compressed > 0 ? compressed : -1;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_ink_mol_raw_1cast_NativeBridge_lz4CompressBound(
        JNIEnv* /*env*/, jclass /*clazz*/, jint data_size) {
    return LZ4_compressBound(data_size);
}

extern "C" JNIEXPORT jint JNICALL
Java_ink_mol_raw_1cast_NativeBridge_lz4Compress(
        JNIEnv* env, jclass /*clazz*/,
        jobject src, jint src_pos, jint src_len,
        jobject dst, jint dst_pos, jint dst_cap) {
    void* sp = env->GetDirectBufferAddress(src);
    void* dp = env->GetDirectBufferAddress(dst);
    CHECKED(-1, sp && dp, "non-direct buffer passed to lz4Compress");
    return LZ4_compress_default(static_cast<const char*>(sp) + src_pos,
                                static_cast<char*>(dp) + dst_pos,
                                src_len, dst_cap);
}
