package ink.mol.raw_cast

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

/**
 * Thin Kotlin facade over the C++ JNI helpers. All routines write into a
 * caller-supplied direct ByteBuffer starting at its current position and
 * return the number of bytes written, or -1 on failure (in which case the
 * caller should fall back to a pure-JVM path).
 */
object NativeBridge {

    @JvmStatic
    external fun copyHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        byteBuffer: ByteBuffer,
        position: Int,
        limit: Int,
        targetFormat: Int,
        lz4Compress: Boolean
    ): Int

    @JvmStatic
    external fun copyBitmap(
        bitmap: Bitmap,
        byteBuffer: ByteBuffer,
        position: Int,
        limit: Int,
        targetFormat: Int,
        lz4Compress: Boolean
    ): Int

    @JvmStatic
    external fun lz4CompressBound(dataSize: Int): Int

    @JvmStatic
    external fun lz4Compress(
        src: ByteBuffer, srcPos: Int, srcLen: Int,
        dst: ByteBuffer, dstPos: Int, dstCap: Int,
    ): Int
}
