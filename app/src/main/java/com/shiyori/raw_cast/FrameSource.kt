package com.shiyori.raw_cast

import android.graphics.Bitmap
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encapsulates one full screenshot pipeline:
 *   1. resolve target resolution + rotation
 *   2. ScreenCaptor.captureBuffer(...) -> HardwareBuffer on S+ raw paths,
 *      or ScreenCaptor.screenshot(...) -> Bitmap otherwise
 *   3. encode into RAW (with optional LZ4) or PNG/WEBP
 *
 * Single-threaded by design (one instance per transport). The underlying
 * SurfaceControl args are cached inside [ScreenCaptor].
 */
class FrameSource {
    private val displayUtil = DisplayUtil()
    private val seq = AtomicInteger(0)
    private val directBuffer = object : ThreadLocal<ByteBuffer>() {}

    @Volatile private var fallbackWidth: Int = 0
    @Volatile private var fallbackHeight: Int = 0

    fun setFallbackSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            fallbackWidth = w
            fallbackHeight = h
        }
    }

    /**
     * Resolve the (w, h) used for this capture. Honors caller-supplied size,
     * else the cached fallback, else queries IWindowManager, else returns
     * a 720x1080 placeholder.
     */
    private fun resolveSize(req: CaptureRequest): Pair<Int, Int> {
        if (req.width > 0 && req.height > 0) {
            fallbackWidth = req.width
            fallbackHeight = req.height
            return req.width to req.height
        }
        if (fallbackWidth > 0 && fallbackHeight > 0) {
            return fallbackWidth to fallbackHeight
        }
        val p: Point = displayUtil.getCurrentDisplaySize()
        return if (p.x > 0 && p.y > 0) {
            fallbackWidth = p.x; fallbackHeight = p.y
            p.x to p.y
        } else 720 to 1080
    }

    fun capture(req: CaptureRequest): EncodedFrame {
        var (w, h) = resolveSize(req)
        // Match physical orientation just like DroidCast_raw does.
        val rotation = displayUtil.getScreenRotation()
        if (rotation == 1 || rotation == 3) {
            val tmp = w; w = h; h = tmp
        }

        if (req.format.isRaw) {
            val captured = ScreenCaptor.captureBuffer(w, h, req.format.surfacePixelFormat())
            if (captured != null) {
                try {
                    return encodeRaw(captured, w, h, req)
                } finally {
                    captured.close()
                }
            }
        }

        val bitmap: Bitmap = ScreenCaptor.screenshot(w, h, req.format.surfacePixelFormat())
            ?: error("ScreenCaptor returned null bitmap")
        try {
            return if (req.format.isRaw) encodeRaw(bitmap, w, h, req)
            else encodeCompressed(bitmap, w, h, req)
        } finally {
            bitmap.recycle()
        }
    }

    // ---- raw -----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun encodeRaw(
        captured: ScreenCaptor.CapturedScreen,
        w: Int,
        h: Int,
        req: CaptureRequest
    ): EncodedFrame {
        val rawSize = w * h * req.format.bytesPerPixel
        val cap = if (req.lz4) NativeBridge.lz4CompressBound(rawSize) else rawSize
        val buf: ByteBuffer = reusableDirectBuffer(cap)

        val written = if (ScreenCaptor.nativeAvailable) {
            NativeBridge.copyHardwareBuffer(
                captured.hardwareBuffer, buf, 0, buf.capacity(), req.format.id, req.lz4
            )
        } else {
            -1
        }

        if (written >= 0) {
            return encodedRawFrame(buf, written, w, h, req)
        }

        val bitmap = captured.asBitmap() ?: error("ScreenCaptor returned null bitmap")
        try {
            return encodeRaw(bitmap, w, h, req, tryHardwareBuffer = false)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeRaw(
        bitmap: Bitmap,
        w: Int,
        h: Int,
        req: CaptureRequest,
        tryHardwareBuffer: Boolean = true
    ): EncodedFrame {
        val rawSize = w * h * req.format.bytesPerPixel
        val cap = if (req.lz4) NativeBridge.lz4CompressBound(rawSize) else rawSize
        val buf: ByteBuffer = reusableDirectBuffer(cap)

        var written = -1
        if (tryHardwareBuffer && ScreenCaptor.nativeAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && bitmap.config == Bitmap.Config.HARDWARE
        ) {
            val hb = bitmap.hardwareBuffer
            if (hb != null) {
                written = NativeBridge.copyHardwareBuffer(
                    hb, buf, 0, buf.capacity(), req.format.id, req.lz4
                )
            }
        }

        if (written < 0) {
            // Fallback: convert to ARGB/565 first, then run NativeBridge against it.
            val cfg = req.format.bitmapConfig()
            val soft = if (bitmap.config == cfg) bitmap else bitmap.copy(cfg, false)
            written = NativeBridge.copyBitmap(soft, buf, 0, buf.capacity(), req.format.id, req.lz4)
            if (soft !== bitmap) soft.recycle()
        }

        if (written < 0) {
            // Last-resort pure-JVM path: just copy pixels (only valid for the
            // requested format if we already converted to it above).
            val fallback = ByteArray(rawSize)
            val bb = ByteBuffer.wrap(fallback)
            bitmap.copyPixelsToBuffer(bb)
            return EncodedFrame(
                payload = ByteArrayPayload(fallback), width = w, height = h, format = req.format,
                lz4 = false, stride = 0, seq = seq.incrementAndGet(),
                timestampMs = (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt()
            )
        }

        return encodedRawFrame(buf, written, w, h, req)
    }

    private fun encodedRawFrame(
        buf: ByteBuffer,
        written: Int,
        w: Int,
        h: Int,
        req: CaptureRequest
    ): EncodedFrame = EncodedFrame(
        payload = ByteBufferPayload(buf, 0, written), width = w, height = h, format = req.format,
        lz4 = req.lz4, stride = if (req.lz4) 0 else w * req.format.bytesPerPixel,
        seq = seq.incrementAndGet(),
        timestampMs = (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt(),
    )

    private fun reusableDirectBuffer(capacity: Int): ByteBuffer {
        val current = directBuffer.get()
        val buf = if (current == null || current.capacity() < capacity) {
            ByteBuffer.allocateDirect(capacity).also { directBuffer.set(it) }
        } else {
            current
        }
        buf.clear()
        buf.limit(capacity)
        return buf
    }

    // ---- compressed ----------------------------------------------------------

    private fun encodeCompressed(bitmap: Bitmap, w: Int, h: Int, req: CaptureRequest): EncodedFrame {
        val (cf, mime) = when (req.format) {
            PixelFmt.PNG  -> Bitmap.CompressFormat.PNG  to "image/png"
            PixelFmt.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (req.quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS to "image/webp"
                    else Bitmap.CompressFormat.WEBP_LOSSY to "image/webp"
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP to "image/webp"
                }
            }
            else -> error("not a compressed format: ${req.format}")
        }
        val src = if (bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val baos = ByteArrayOutputStream(64 * 1024)
        try {
            src.compress(cf, req.quality.coerceIn(1, 100), baos)
        } finally {
            if (src !== bitmap) src.recycle()
        }
        Log.i("raw_cast", "encoded ${mime} ${w}x${h} -> ${baos.size()}B")
        return EncodedFrame(
            payload = ByteArrayPayload(baos.toByteArray()), width = w, height = h, format = req.format,
            lz4 = false, stride = 0, seq = seq.incrementAndGet(),
            timestampMs = (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt(),
        )
    }
}
