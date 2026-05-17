package ink.mol.raw_cast.server

import android.util.Log
import ink.mol.raw_cast.CaptureRequest
import ink.mol.raw_cast.Frame
import ink.mol.raw_cast.FrameMux
import ink.mol.raw_cast.FrameSource
import ink.mol.raw_cast.PixelFmt
import java.io.BufferedOutputStream
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
 * /  ink.mol.raw_cast.Main --mode=stdout ...`, every screenshot frame is
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
    private val slowFrameThresholdMs = 1_000L

    fun run(req: CaptureRequest, fps: Int, oneShot: Boolean) {
        // Use the raw FD so nothing the JVM may have layered on top of System.out
        // gets in the way (e.g. a PrintStream that converts \n -> \r\n on Windows
        // shells).
        val fos = FileOutputStream(FileDescriptor.out)
        val out = BufferedOutputStream(fos, 1 shl 20)
        try {
            val banner = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            banner.putInt(Frame.MAGIC); banner.putInt(1)
            out.write(banner.array())
            out.flush()

            if (oneShot || fps == 0) {
                val f = source.capture(req)
                FrameMux.writeTo(out, f)
                out.flush()
                return
            }

            val periodMs = (1000.0 / fps).toLong().coerceAtLeast(1L)
            while (true) {
                val started = System.nanoTime()
                val captureStarted = started
                val f = source.capture(req)
                val captureMs = (System.nanoTime() - captureStarted) / 1_000_000
                val writeStarted = System.nanoTime()
                FrameMux.writeTo(out, f)
                out.flush()
                val writeMs = (System.nanoTime() - writeStarted) / 1_000_000
                if (captureMs >= slowFrameThresholdMs || writeMs >= slowFrameThresholdMs) {
                    Log.w(
                        TAG,
                        "slow stdout frame seq=${f.seq} format=${f.format} lz4=${f.lz4} " +
                            "payload=${f.data.size}B capture=${captureMs}ms write=${writeMs}ms"
                    )
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val sleep = periodMs - elapsedMs
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { return }
            }
        } catch (e: Exception) {
            // Logcat-only because stdout might already be broken.
            Log.w(TAG, "stdout sink ended: ${e.message}")
        } finally {
            try { out.flush() } catch (_: Throwable) {}
            try { out.close() } catch (_: Throwable) {}
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
