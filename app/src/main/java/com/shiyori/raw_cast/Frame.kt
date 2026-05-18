package com.shiyori.raw_cast

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * raw_cast wire-frame header (32 bytes, little-endian).
 *
 *   offset  size  field
 *   0       4     magic        = 'R','C','0','1'
 *   4       1     version      = 1
 *   5       1     flags        bit0 = LZ4 compressed payload
 *   6       2     format       PixelFmt.id
 *   8       4     seq          monotonic sequence number
 *   12      4     width        pixels
 *   16      4     height       pixels
 *   20      4     stride       bytes per row in payload (0 = width*bpp tightly packed)
 *   24      4     payload_size bytes following the header
 *   28      4     timestamp_ms wall-clock since boot, lower 32 bits
 *
 * The same header is used by every framed transport (raw TCP, HTTP chunked
 * stream, stdout) so that one client decoder works against all of them.
 */
object Frame {
    const val MAGIC: Int = 0x31304352.toInt() // little-endian 'R','C','0','1'
    const val HEADER_SIZE: Int = 32
    const val VERSION: Byte = 1

    const val FLAG_LZ4: Int = 0x01

    fun writeHeader(
        dst: ByteBuffer,
        format: Int,
        flags: Int,
        seq: Int,
        width: Int,
        height: Int,
        stride: Int,
        payloadSize: Int,
        timestampMs: Int,
    ) {
        dst.order(ByteOrder.LITTLE_ENDIAN)
        dst.putInt(MAGIC)
        dst.put(VERSION)
        dst.put(flags.toByte())
        dst.putShort(format.toShort())
        dst.putInt(seq)
        dst.putInt(width)
        dst.putInt(height)
        dst.putInt(stride)
        dst.putInt(payloadSize)
        dst.putInt(timestampMs)
    }

    /**
     * Allocate a single contiguous direct buffer big enough to hold one frame
     * header followed by [payloadCapacity] bytes of payload, with the position
     * already advanced past the header (callers fill in payload first, then
     * back-fill the header via [backfillHeader]).
     */
    fun allocate(payloadCapacity: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(HEADER_SIZE + payloadCapacity)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { it.position(HEADER_SIZE) }
    }

    fun backfillHeader(
        buf: ByteBuffer,
        format: Int, flags: Int, seq: Int,
        width: Int, height: Int, stride: Int,
        timestampMs: Int,
    ) {
        val payloadSize = buf.position() - HEADER_SIZE
        val saved = buf.position()
        buf.position(0)
        writeHeader(buf, format, flags, seq, width, height, stride, payloadSize, timestampMs)
        buf.position(saved)
    }
}
