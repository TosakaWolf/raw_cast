package ink.mol.raw_cast

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Serialises an [EncodedFrame] into one contiguous byte array containing the
 * 32-byte raw_cast header followed by payload bytes. Used by framed transports
 * (raw TCP / HTTP chunked stream / stdout).
 */
object FrameMux {
    fun frameSize(frame: EncodedFrame): Int = Frame.HEADER_SIZE + frame.data.size

    fun writeTo(output: OutputStream, frame: EncodedFrame) {
        output.write(headerBytes(frame))
        output.write(frame.data)
    }

    fun pack(frame: EncodedFrame): ByteArray {
        val out = ByteArray(frameSize(frame))
        val bb = ByteBuffer.wrap(out)
        writeHeader(bb, frame)
        System.arraycopy(frame.data, 0, out, Frame.HEADER_SIZE, frame.data.size)
        return out
    }

    private fun headerBytes(frame: EncodedFrame): ByteArray {
        val header = ByteArray(Frame.HEADER_SIZE)
        writeHeader(ByteBuffer.wrap(header), frame)
        return header
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
            payloadSize = frame.data.size,
            timestampMs = frame.timestampMs,
        )
    }
}
