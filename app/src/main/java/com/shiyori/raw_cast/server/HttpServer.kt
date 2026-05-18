package com.shiyori.raw_cast.server

import android.util.Log
import com.shiyori.raw_cast.CaptureRequest
import com.shiyori.raw_cast.EncodedFrame
import com.shiyori.raw_cast.FrameMux
import com.shiyori.raw_cast.FrameSource
import com.shiyori.raw_cast.PixelFmt
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val TAG = "raw_cast"
private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

/**
 * Small HTTP/1.1 server using persistent connections by default.
 *
 * Endpoints:
 *   GET /screenshot -> one frame payload, with metadata in X-Frame-* headers
 *   GET /preview    -> one PNG/WEBP frame for browsers, defaulting to PNG
 *   GET /stream     -> chunked response; each chunk is one packed RC01 frame
 */
class HttpServer(
    private val source: FrameSource,
    private val port: Int,
    private val maxRetries: Int = 10,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    @Volatile var actualPort: Int = 0
        private set

    fun start(): PortBinder.BindResult {
        val (result, ss) = PortBinder.bindTcp(port, "HTTP/1.1", maxRetries)
        if (!result.success || ss == null) {
            return result
        }
        serverSocket = ss
        actualPort = result.actualPort
        Log.i(TAG, "HTTP/1.1 listening on :$actualPort")
        acceptThread = Thread({
            while (!Thread.currentThread().isInterrupted && !ss.isClosed) {
                val sock = try {
                    ss.accept()
                } catch (e: Exception) {
                    Log.w(TAG, "http accept ended: ${e.message}")
                    return@Thread
                }
                Thread({ handleClient(sock) }, "raw_cast-http-${sock.port}").apply {
                    isDaemon = true
                }.start()
            }
        }, "raw_cast-http-accept").apply { isDaemon = true; start() }
        return result
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Throwable) {}
        acceptThread?.interrupt()
    }

    private fun handleClient(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.sendBufferSize = 1 shl 20
            sock.soTimeout = 0
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            while (!sock.isClosed) {
                val req = readRequest(input) ?: break
                val keepAlive = shouldKeepAlive(req)
                val streaming = handleRequest(output, req, keepAlive)
                if (!keepAlive || streaming) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "http client ended: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun handleRequest(output: OutputStream, req: HttpRequest, keepAlive: Boolean): Boolean {
        if (!req.method.equals("GET", ignoreCase = true)) {
            sendFixed(output, "405 Method Not Allowed", "text/plain; charset=utf-8",
                "method not allowed".toByteArray(Charsets.UTF_8), keepAlive)
            return false
        }

        val path = pathOf(req.target)
        val q = parseQuery(req.target)
        return try {
            when (path) {
                "/" -> {
                    sendFixed(output, "200 OK", "text/html; charset=utf-8",
                        indexHtml().toByteArray(Charsets.UTF_8), keepAlive)
                    false
                }
                "/screenshot" -> {
                    sendOneShot(output, q, preview = false, keepAlive = keepAlive)
                    false
                }
                "/preview" -> {
                    sendOneShot(output, q, preview = true, keepAlive = keepAlive)
                    false
                }
                "/stream" -> {
                    stream(output, q, keepAlive)
                    true
                }
                else -> {
                    sendFixed(output, "404 Not Found", "text/plain; charset=utf-8",
                        "not found".toByteArray(Charsets.UTF_8), keepAlive)
                    false
                }
            }
        } catch (e: IllegalArgumentException) {
            sendFixed(output, "400 Bad Request", "text/plain; charset=utf-8",
                "bad request: ${e.message}".toByteArray(Charsets.UTF_8), keepAlive)
            false
        } catch (e: Exception) {
            Log.w(TAG, "http request failed: ${e.message}")
            sendFixed(output, "500 Internal Server Error", "text/plain; charset=utf-8",
                "capture failed: ${e.message}".toByteArray(Charsets.UTF_8), keepAlive)
            false
        }
    }

    private fun sendOneShot(
        output: OutputStream,
        q: Map<String, String>,
        preview: Boolean,
        keepAlive: Boolean,
    ) {
        val frame = captureFrame(q, preview)
        val contentType = if (frame.format.isRaw) "application/octet-stream" else frame.format.mime()
        sendFixed(
            output = output,
            status = "200 OK",
            contentType = contentType,
            body = frame.data,
            keepAlive = keepAlive,
            headers = frameHeaders(frame),
        )
    }

    private fun stream(output: OutputStream, q: Map<String, String>, keepAlive: Boolean) {
        val req = captureRequest(q, preview = false)
        val fps = q["fps"]?.toIntOrNull()?.coerceIn(1, 120) ?: 30
        val periodMs = (1000.0 / fps).toLong().coerceAtLeast(1L)

        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/x-raw-cast-frames\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Raw-Cast-Protocol-Version: 1\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(head)
        output.flush()

        try {
            while (true) {
                val started = System.nanoTime()
                val frame = source.capture(req)
                writeChunk(output, frame)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val sleep = periodMs - elapsed
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "http stream ended: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "http stream failed: ${e.message}")
        }
        try {
            output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        } catch (_: Throwable) {
        }
    }

    private fun writeChunk(output: OutputStream, frame: EncodedFrame) {
        output.write(FrameMux.frameSize(frame).toString(16).toByteArray(StandardCharsets.US_ASCII))
        output.write(CRLF)
        FrameMux.writeTo(output, frame)
        output.write(CRLF)
        output.flush()
    }

    private fun captureFrame(q: Map<String, String>, preview: Boolean): EncodedFrame {
        return source.capture(captureRequest(q, preview))
    }

    private fun captureRequest(q: Map<String, String>, preview: Boolean): CaptureRequest {
        var format = PixelFmt.parse(q["format"])
        if (preview && format.isRaw) {
            format = PixelFmt.PNG
        }
        return CaptureRequest(
            width = q["width"]?.toIntOrNull() ?: 0,
            height = q["height"]?.toIntOrNull() ?: 0,
            format = format,
            lz4 = q["compress"]?.lowercase() == "lz4" && format.isRaw,
            quality = q["quality"]?.toIntOrNull()?.coerceIn(1, 100) ?: 100,
        )
    }

    private fun frameHeaders(frame: EncodedFrame): List<Pair<String, String>> = listOf(
        "X-Frame-Width" to frame.width.toString(),
        "X-Frame-Height" to frame.height.toString(),
        "X-Frame-Format" to frame.format.name,
        "X-Frame-Lz4" to if (frame.lz4) "1" else "0",
        "X-Frame-Seq" to frame.seq.toString(),
    )

    private fun sendFixed(
        output: OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        keepAlive: Boolean,
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(head)
        output.write(body)
        output.flush()
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val version: String,
        val headers: Map<String, String>,
    )

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 3) throw IOException("bad request line")

        val headers = HashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: throw IOException("unexpected EOF in headers")
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) {
                headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
            }
        }
        return HttpRequest(parts[0], parts[1], parts[2], headers)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() == 0) null else String(out.toByteArray(), StandardCharsets.US_ASCII)
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) throw IOException("HTTP line too long")
        }
        return String(out.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun shouldKeepAlive(req: HttpRequest): Boolean {
        val conn = req.headers["connection"]?.lowercase()
        return if (req.version.equals("HTTP/1.1", ignoreCase = true)) {
            conn != "close"
        } else {
            conn == "keep-alive"
        }
    }

    private fun pathOf(target: String): String {
        val pathAndQuery = if (target.startsWith("http://", ignoreCase = true)) {
            val afterHost = target.substringAfter("://").substringAfter('/', "")
            "/$afterHost"
        } else {
            target
        }
        return pathAndQuery.substringBefore('?').ifEmpty { "/" }
    }

    private fun parseQuery(target: String): Map<String, String> {
        val q = target.indexOf('?')
        if (q < 0 || q == target.lastIndex) return emptyMap()
        val m = HashMap<String, String>()
        for (kv in target.substring(q + 1).split('&')) {
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            val key = if (eq < 0) kv else kv.substring(0, eq)
            val value = if (eq < 0) "" else kv.substring(eq + 1)
            m[decodeUrl(key)] = decodeUrl(value)
        }
        return m
    }

    private fun decodeUrl(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }

    private fun indexHtml(): String = """
        <html><body style="font-family:monospace">
        <h2>raw_cast</h2>
        <ul>
          <li><a href="/preview">/preview</a> - browser preview</li>
          <li><a href="/preview?format=webp&quality=80">/preview?format=webp&amp;quality=80</a></li>
          <li><a href="/screenshot?format=png">/screenshot?format=png</a></li>
        </ul>
        <p>HTTP/1.1 keep-alive endpoints: /screenshot, /preview, /stream.</p>
        </body></html>
    """.trimIndent()
}
