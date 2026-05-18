package com.shiyori.raw_cast

import android.os.Looper
import com.shiyori.raw_cast.server.HttpServer
import com.shiyori.raw_cast.server.PortBinder
import com.shiyori.raw_cast.server.RawTcpServer
import com.shiyori.raw_cast.server.StdoutSink

/**
 * Real entry point. Parses CLI options, starts every requested transport in
 * its own thread (or runs the stdout sink synchronously) and then either
 * blocks on the main looper (network mode) or returns when the stdout/one-shot
 * job finishes.
 *
 * In network mode, prints a structured status table to stdout so host-side
 * automation can parse the PID and actual ports. Stderr stays human/log-only.
 *
 * Format of each BIND line:
 *   BIND:<transport>=<actual_port>
 * or on failure:
 *   BIND:<transport>=FAILED
 *
 * Example:
 *   PID=12345
 *   BIND:HTTP=53516
 *   BIND:TCP=53517
 *   READY=1
 */
class KtMain {
    fun main(args: Array<String>) {
        // Surface any unhandled exception to logcat and stderr so non-installed
        // launches show useful failure output.
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            System.err.println("[raw_cast] uncaught in ${t.name}: $e")
            e.printStackTrace(System.err)
        }

        val opts = CliOptions.parse(args)
        System.err.println(">>> raw_cast starting")
        System.err.print(opts.summary())

        val source = FrameSource()
        opts.defaultRequest.let {
            if (it.width > 0 && it.height > 0) source.setFallbackSize(it.width, it.height)
        }

        if (opts.stdoutMode) {
            StdoutSink(source).run(opts.defaultRequest, opts.defaultFps, opts.oneShot)
            return
        }

        emitStatus("PID=${android.os.Process.myPid()}")

        val results = mutableListOf<PortBinder.BindResult>()

        if (opts.httpPort > 0) {
            val r = HttpServer(source, opts.httpPort, opts.portRetries).start()
            results.add(r)
        }
        if (opts.tcpPort > 0) {
            val r = RawTcpServer(source, opts.tcpPort, opts.portRetries).start()
            results.add(r)
        }

        // Print structured status lines for host-side parsing. This is network
        // mode only; stdout mode has already returned to the binary sink above.
        for (r in results) {
            val tag = transportTag(r.transport)
            if (r.success) {
                emitStatus("BIND:$tag=${r.actualPort}")
            } else {
                emitStatus("BIND:$tag=FAILED")
            }
        }

        val succeeded = results.count { it.success }
        if (succeeded == 0) {
            if (results.isEmpty()) {
                System.err.println("[raw_cast] no transports enabled. Try --port=53516, --tcp=53517, or --mode=stdout")
            } else {
                System.err.println("[raw_cast] ALL transports failed to bind. Check if ports are occupied.")
            }
            emitStatus("READY=0")
            return
        }

        // Report fallback warnings prominently.
        for (r in results.filter { it.fallback }) {
            System.err.println("[raw_cast] WARNING: ${r.transport} requested port ${r.requested} was busy, bound on ${r.actualPort} instead")
        }

        System.err.println(">>> raw_cast ready ($succeeded/${results.size} transports active)")
        emitStatus("READY=1")

        // Block forever on the main Looper so the process stays alive.
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        Looper.loop()
    }

    private fun transportTag(name: String): String = when {
        name.contains("HTTP") -> "HTTP"
        name.contains("TCP") -> "TCP"
        else -> name.uppercase().replace(' ', '_')
    }

    private fun emitStatus(line: String) {
        System.out.println(line)
        System.out.flush()
    }
}
