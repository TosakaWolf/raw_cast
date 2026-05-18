package com.shiyori.raw_cast

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/**
 * Serialises an [EncodedFrame] into the 32-byte raw_cast header followed by
 * payload bytes. Channel writes avoid copying direct raw payloads into a large
 * heap byte array first.
 */
object FrameMux {
    private val headerBuffer = object : ThreadLocal<ByteBuffer>() {}
    private val headerByteArray = object : ThreadLocal<ByteArray>() {}
    private val outputScratch = object : ThreadLocal<ByteArray>() {}

    fun frameSize(frame: EncodedFrame): Int = Frame.HEADER_SIZE + frame.payloadSize

    fun writeTo(output: OutputStream, frame: EncodedFrame) {
        output.write(headerBytes(frame))
        writePayloadTo(output, frame)
    }

    fun writePayloadTo(output: OutputStream, frame: EncodedFrame) {
        when (val payload = frame.payload) {
            is ByteArrayPayload -> output.write(payload.bytes)
            is ByteBufferPayload -> writeBufferToOutput(output, payload.duplicateForRead())
        }
    }

    fun writeTo(channel: WritableByteChannel, frame: EncodedFrame) {
        writeFully(channel, headerByteBuffer(frame))
        when (val payload = frame.payload) {
            is ByteArrayPayload -> writeFully(channel, ByteBuffer.wrap(payload.bytes))
            is ByteBufferPayload -> writeFully(channel, payload.duplicateForRead())
        }
    }

    fun pack(frame: EncodedFrame): ByteArray {
        val out = ByteArray(frameSize(frame))
        val bb = ByteBuffer.wrap(out)
        writeHeader(bb, frame)
        when (val payload = frame.payload) {
            is ByteArrayPayload -> {
                System.arraycopy(payload.bytes, 0, out, Frame.HEADER_SIZE, payload.bytes.size)
            }
            is ByteBufferPayload -> {
                payload.duplicateForRead().get(out, Frame.HEADER_SIZE, payload.size)
            }
        }
        return out
    }

    private fun headerBytes(frame: EncodedFrame): ByteArray {
        val header = headerByteArray.get() ?: ByteArray(Frame.HEADER_SIZE).also {
            headerByteArray.set(it)
        }
        writeHeader(ByteBuffer.wrap(header), frame)
        return header
    }

    private fun headerByteBuffer(frame: EncodedFrame): ByteBuffer {
        val bb = headerBuffer.get() ?: ByteBuffer.allocateDirect(Frame.HEADER_SIZE).also {
            headerBuffer.set(it)
        }
        bb.clear()
        writeHeader(bb, frame)
        bb.flip()
        return bb
    }

    private fun writeHeader(bb: ByteBuffer, frame: EncodedFrame) {
        Frame.writeHeader(
            bb,
            format = frame.format.id,
            flags = frame.flags,
            seq = frame.seq,
            width = frame.width,
            height = frame.height,
            stride = frame.stride,
            payloadSize = frame.payloadSize,
            timestampMs = frame.timestampMs,
        )
    }

    private fun writeFully(channel: WritableByteChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun writeBufferToOutput(output: OutputStream, buffer: ByteBuffer) {
        val scratch = outputScratch.get() ?: ByteArray(64 * 1024).also {
            outputScratch.set(it)
        }
        while (buffer.hasRemaining()) {
            val n = minOf(buffer.remaining(), scratch.size)
            buffer.get(scratch, 0, n)
            output.write(scratch, 0, n)
        }
    }
}
