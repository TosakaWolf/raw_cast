package com.shiyori.raw_cast.server

import android.util.Log
import com.shiyori.raw_cast.CaptureRequest
import com.shiyori.raw_cast.Frame
import com.shiyori.raw_cast.FrameMux
import com.shiyori.raw_cast.FrameSource
import com.shiyori.raw_cast.PixelFmt
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "raw_cast"

/**
 * ADB stdout sink. The fastest possible delivery path on a USB-connected
 * device because it skips both the kernel's loopback adb-forward proxy and
 * the userland HTTP framing.
 *
 * Triggered by passing `--mode=stdout` (and optional --format/--fps/--lz4/...)
 * on the command line. Once the process is started by `adb shell ... app_process
 * /  com.shiyori.raw_cast.Main --mode=stdout ...`, every screenshot frame is
 * appended to stdout as `8-byte banner + repeating Frame(s)`.
 *
 *   banner   = magic 'R','C','0','1' + LE uint32 version(=1)
 *   frame    = 32-byte raw_cast header (Frame) + payload bytes
 *
 * Stderr is reserved for log lines so that the binary stream on stdout stays
 * clean and parseable on the PC side.
 *
 * Important: every Android android.util.Log call also writes to logcat, but
 * `println()` on JVM normally goes to stdout. We rebind stdout to FileDescriptor.out
 * so it survives even if a previous command tampered with System.out.
 */
class StdoutSink(
    private val source: FrameSource,
) {
    fun run(req: CaptureRequest, fps: Int, oneShot: Boolean) {
        // Use the raw FD so nothing the JVM may have layered on top of System.out
        // gets in the way (e.g. a PrintStream that converts \n -> \r\n on Windows
        // shells).
        val fos = FileOutputStream(FileDescriptor.out)
        val channel = fos.channel
        try {
            val banner = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            banner.putInt(Frame.MAGIC); banner.putInt(1)
            banner.flip()
            while (banner.hasRemaining()) {
                channel.write(banner)
            }

            if (oneShot || fps == 0) {
                val f = source.capture(req)
                FrameMux.writeTo(channel, f)
                return
            }

            val periodMs = (1000.0 / fps).toLong().coerceAtLeast(1L)
            while (true) {
                val started = System.nanoTime()
                val f = source.capture(req)
                FrameMux.writeTo(channel, f)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val sleep = periodMs - elapsedMs
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { return }
            }
        } catch (e: Exception) {
            // Logcat-only because stdout might already be broken.
            Log.w(TAG, "stdout sink ended: ${e.message}")
        } finally {
            try { fos.close() } catch (_: Throwable) {}
        }
    }
}

object StdoutDefaults {
    fun defaultRequest(): CaptureRequest = CaptureRequest(
        width = 0, height = 0,
        format = PixelFmt.RAW_RGB565,
        lz4 = false,
        quality = 100,
    )
}
