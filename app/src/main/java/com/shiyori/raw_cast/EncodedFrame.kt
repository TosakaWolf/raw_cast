package com.shiyori.raw_cast

import java.nio.ByteBuffer

/**
 * A produced frame, ready to be wrapped by any transport (HTTP body, raw TCP
 * frame, HTTP chunked stream, stdout).
 *
 *  - [payload] holds either RAW pixels (when [format].isRaw) or the compressed
 *    bytes of a PNG/WEBP image. When [lz4] is true, RAW pixels have been further
 *    compressed with LZ4 (payload only, header is not compressed).
 *  - [stride] is the row stride in bytes for raw payloads (0 = tightly packed).
 *
 * The same struct is also used to produce the on-the-wire framed bytes via
 * [FrameMux] when the transport is not a plain HTTP body.
 */
class EncodedFrame(
    val payload: FramePayload,
    val width: Int,
    val height: Int,
    val format: PixelFmt,
    val lz4: Boolean,
    val stride: Int,
    val seq: Int,
    val timestampMs: Int,
) {
    val flags: Int get() = if (lz4) Frame.FLAG_LZ4 else 0
    val data: ByteArray get() = payload.toByteArray()
    val payloadSize: Int get() = payload.size
}

sealed interface FramePayload {
    val size: Int
    fun toByteArray(): ByteArray
}

class ByteArrayPayload(
    val bytes: ByteArray,
) : FramePayload {
    override val size: Int get() = bytes.size
    override fun toByteArray(): ByteArray = bytes
}

class ByteBufferPayload(
    private val buffer: ByteBuffer,
    private val offset: Int,
    override val size: Int,
) : FramePayload {
    fun duplicateForRead(): ByteBuffer {
        val dup = buffer.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        return dup.slice()
    }

    override fun toByteArray(): ByteArray {
        val out = ByteArray(size)
        duplicateForRead().get(out)
        return out
    }
}
