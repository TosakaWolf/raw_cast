package com.shiyori.raw_cast

import android.graphics.Bitmap
import android.graphics.PixelFormat

/**
 * Pixel / image formats exposed by raw_cast.
 *
 * The numeric ids form part of the wire protocol (FrameHeader.format) and must
 * stay stable across releases.
 *
 *   1  RAW_RGB565    (2 bytes/pixel) - identical to DroidCast_raw default; smallest raw bandwidth
 *   2  RAW_RGBA8888  (4 bytes/pixel) - Android-native 4-channel order
 *   3  reserved      (legacy 4-channel raw id)
 *   11 PNG           (compressed, lossless)
 *   12 WEBP          (compressed, configurable lossless/lossy)
 */
enum class PixelFmt(val id: Int, val isRaw: Boolean, val bytesPerPixel: Int) {
    RAW_RGB565(1, true, 2),
    RAW_RGBA8888(2, true, 4),
    PNG(11, false, 0),
    WEBP(12, false, 0);

    companion object {
        fun parse(s: String?): PixelFmt {
            val normalized = s?.trim()?.lowercase()
            return when (normalized) {
                null, "", "rgb565", "raw_rgb565" -> RAW_RGB565
                "rgba", "rgba8888", "raw_rgba", "raw_rgba8888" -> RAW_RGBA8888
                "png" -> PNG
                "webp" -> WEBP
                else -> throw IllegalArgumentException(
                    "unsupported format '$s'; supported formats: rgb565, rgba, png, webp"
                )
            }
        }

        fun fromId(id: Int): PixelFmt = values().firstOrNull { it.id == id } ?: RAW_RGB565
    }

    /**
     * Returns the underlying Surface PixelFormat constant to request from the
     * SurfaceControl screenshot pipeline so that the GPU produces the desired
     * byte layout directly when possible.
     */
    fun surfacePixelFormat(): Int? = when (this) {
        RAW_RGB565 -> PixelFormat.RGB_565
        RAW_RGBA8888 -> PixelFormat.RGBA_8888
        // For compressed formats let the system pick whatever is fastest
        // (typically RGBA_8888 on modern devices).
        PNG, WEBP -> null
    }

    fun bitmapConfig(): Bitmap.Config = when (this) {
        RAW_RGB565 -> Bitmap.Config.RGB_565
        else -> Bitmap.Config.ARGB_8888
    }

    fun mime(): String = when (this) {
        PNG -> "image/png"
        WEBP -> "image/webp"
        else -> "application/octet-stream"
    }
}
