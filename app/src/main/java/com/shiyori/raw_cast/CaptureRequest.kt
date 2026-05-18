package com.shiyori.raw_cast

/**
 * Single screenshot request, decoupled from any transport.
 *
 *   - [width] / [height]: 0 means "use the device display size at capture time"
 *   - [format]: output pixel/image format (raw or compressed)
 *   - [lz4]:   if [format].isRaw, additionally LZ4-compress the payload
 *   - [quality]: only used by WEBP (1..100)
 */
data class CaptureRequest(
    val width: Int = 0,
    val height: Int = 0,
    val format: PixelFmt = PixelFmt.RAW_RGB565,
    val lz4: Boolean = false,
    val quality: Int = 100,
)
