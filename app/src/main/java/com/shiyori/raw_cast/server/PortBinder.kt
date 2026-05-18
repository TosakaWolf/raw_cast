package com.shiyori.raw_cast.server

import android.util.Log
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.ServerSocketChannel

private const val TAG = "raw_cast"

/**
 * Utility that attempts to bind a TCP [ServerSocket] to a requested port.
 * If the port is already in use it retries on successive
 * ports (port+1, port+2, ...) up to [maxRetries] times before giving up.
 *
 * This solves the common issue where another process (or a previous raw_cast
 * instance that hasn't fully exited yet) still holds the port.
 *
 * All results are surfaced via [BindResult] so the caller can distinguish
 * between "bound on requested port", "bound on fallback port", and "failed".
 */
object PortBinder {

    /**
     * Result of a bind attempt.
     *
     * @property actualPort  The port actually bound (may differ from requested if fallback was used).
     * @property requested   The originally-requested port.
     * @property transport   Human-readable transport name for logging (e.g. "HTTP/1.1", "Raw TCP").
     * @property success     Whether binding succeeded at all.
     * @property error       If [success] is false, the last exception encountered.
     */
    data class BindResult(
        val actualPort: Int,
        val requested: Int,
        val transport: String,
        val success: Boolean,
        val error: Exception? = null,
    ) {
        val fallback: Boolean get() = success && actualPort != requested
    }

    /**
     * Try to create and bind a [ServerSocket] (TCP) on [port]. On
     * [BindException] retries on port+1..port+[maxRetries]-1.
     *
     * @return a [Pair] of BindResult and the ServerSocket (nullable if failed).
     */
    fun bindTcp(
        port: Int,
        transport: String,
        maxRetries: Int = 10,
    ): Pair<BindResult, ServerSocket?> {
        var lastError: Exception? = null
        for (attempt in 0 until maxRetries) {
            val tryPort = port + attempt
            if (tryPort > 65535) break
            try {
                val ss = ServerSocket(tryPort).also { it.reuseAddress = true }
                if (attempt > 0) {
                    Log.w(TAG, "[$transport] port $port busy, fell back to $tryPort")
                }
                return BindResult(tryPort, port, transport, success = true) to ss
            } catch (e: BindException) {
                lastError = e
                Log.w(TAG, "[$transport] port $tryPort in use: ${e.message}")
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "[$transport] unexpected error binding port $tryPort: ${e.message}")
                break // non-recoverable
            }
        }
        Log.e(TAG, "[$transport] FAILED to bind any port in range $port..${port + maxRetries - 1}")
        return BindResult(0, port, transport, success = false, error = lastError) to null
    }

    fun bindTcpChannel(
        port: Int,
        transport: String,
        maxRetries: Int = 10,
    ): Pair<BindResult, ServerSocketChannel?> {
        var lastError: Exception? = null
        for (attempt in 0 until maxRetries) {
            val tryPort = port + attempt
            if (tryPort > 65535) break
            try {
                val channel = ServerSocketChannel.open()
                try {
                    channel.configureBlocking(true)
                    channel.socket().reuseAddress = true
                    channel.socket().bind(InetSocketAddress(tryPort))
                    if (attempt > 0) {
                        Log.w(TAG, "[$transport] port $port busy, fell back to $tryPort")
                    }
                    return BindResult(tryPort, port, transport, success = true) to channel
                } catch (e: Exception) {
                    try { channel.close() } catch (_: Throwable) {}
                    throw e
                }
            } catch (e: BindException) {
                lastError = e
                Log.w(TAG, "[$transport] port $tryPort in use: ${e.message}")
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "[$transport] unexpected error binding port $tryPort: ${e.message}")
                break
            }
        }
        Log.e(TAG, "[$transport] FAILED to bind any port in range $port..${port + maxRetries - 1}")
        return BindResult(0, port, transport, success = false, error = lastError) to null
    }
}
