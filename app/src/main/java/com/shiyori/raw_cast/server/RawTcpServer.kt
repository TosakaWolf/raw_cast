package com.shiyori.raw_cast.server

import android.util.Log
import com.shiyori.raw_cast.CaptureRequest
import com.shiyori.raw_cast.Frame
import com.shiyori.raw_cast.FrameMux
import com.shiyori.raw_cast.FrameSource
import com.shiyori.raw_cast.PixelFmt
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

private const val TAG = "raw_cast"

/**
 * Raw TCP frame server. Designed for the lowest possible per-frame overhead
 * over an `adb forward` tunnel (no HTTP framing and no multipart boundaries).
 *
 * Wire protocol:
 *
 *   1. Client opens a TCP connection.
 *   2. Client writes one ASCII request line terminated by \n, e.g.:
 *        format=rgb565 fps=30 width=0 height=0 compress=none\n
 *      (whitespace separated key=value pairs; unknown keys ignored).
 *      An empty line means "use defaults" (rgb565, 30 fps, native size).
 *   3. Server writes the 8-byte banner: bytes 'R','C','0','1', then a
 *      little-endian uint32 protocol version (currently 1).
 *   4. Server then writes an unbounded stream of frames; each frame is a
 *      32-byte raw_cast header + payload bytes (see com.shiyori.raw_cast.Frame).
 *   5. Client may close the socket at any time; the server detects EOF/IO
 *      error on the next write and tears the session down.
 *
 * fps=0 means "send a single frame and close" (one-shot mode, useful for
 * minimal clients).
 */
class RawTcpServer(
    private val source: FrameSource,
    private val port: Int,
    private val maxRetries: Int = 10,
) {
    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var acceptThread: Thread? = null

    /** The port actually bound (may differ from [port] if fallback was used). 0 if not started. */
    @Volatile var actualPort: Int = 0
        private set

    /**
     * Attempt to bind and start the server.
     * @return [PortBinder.BindResult] describing success/failure and the actual port.
     */
    fun start(): PortBinder.BindResult {
        val (result, server) = PortBinder.bindTcpChannel(port, "Raw TCP", maxRetries)
        if (!result.success || server == null) {
            return result
        }
        serverChannel = server
        actualPort = result.actualPort
        Log.i(TAG, "Raw TCP listening on :$actualPort")
        acceptThread = Thread({
            while (!Thread.currentThread().isInterrupted && server.isOpen) {
                val channel = try {
                    server.accept()
                } catch (e: Exception) {
                    Log.w(TAG, "tcp accept ended: ${e.message}"); return@Thread
                }
                Thread({ handleClient(channel) }, "raw_cast-tcp-${channel.socket().port}").apply {
                    isDaemon = true
                }.start()
            }
        }, "raw_cast-tcp-accept").apply { isDaemon = true; start() }
        return result
    }

    fun stop() {
        try { serverChannel?.close() } catch (_: Throwable) {}
        acceptThread?.interrupt()
    }

    private fun handleClient(channel: SocketChannel) {
        val sock: Socket = channel.socket()
        try {
            channel.configureBlocking(true)
            sock.tcpNoDelay = true
            sock.sendBufferSize = 1 shl 20 // 1 MiB
            sock.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII))

            // Read one request line.
            val requestLine = reader.readLine() ?: ""
            val (req, fps) = parseRequest(requestLine)

            // Banner
            val banner = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            banner.putInt(Frame.MAGIC); banner.putInt(1)
            banner.flip()
            while (banner.hasRemaining()) {
                channel.write(banner)
            }

            if (fps == 0) {
                val frame = source.capture(req)
                FrameMux.writeTo(channel, frame)
                return
            }

            val periodMs = (1000.0 / fps).toLong().coerceAtLeast(1L)
            while (!sock.isClosed) {
                val started = System.nanoTime()
                val frame = source.capture(req)
                FrameMux.writeTo(channel, frame)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val sleep = periodMs - elapsedMs
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tcp client ended: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun parseRequest(line: String): Pair<CaptureRequest, Int> {
        var width = 0; var height = 0
        var format: PixelFmt = PixelFmt.RAW_RGB565
        var compress: String? = null
        var quality = 100
        var fps = 30
        for (token in line.trim().split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val k = token.substring(0, eq).lowercase()
            val v = token.substring(eq + 1)
            when (k) {
                "width" -> width = v.toIntOrNull() ?: 0
                "height" -> height = v.toIntOrNull() ?: 0
                "format" -> format = PixelFmt.parse(v)
                "compress" -> compress = v
                "quality" -> quality = v.toIntOrNull()?.coerceIn(1, 100) ?: 100
                "fps" -> fps = v.toIntOrNull()?.coerceIn(0, 120) ?: 30
            }
        }
        val lz4 = compress?.lowercase() == "lz4" && format.isRaw
        return CaptureRequest(width, height, format, lz4, quality) to fps
    }
}
