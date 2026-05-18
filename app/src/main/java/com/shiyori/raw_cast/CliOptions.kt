package com.shiyori.raw_cast

/**
 * Parsed command-line options for the app_process entry point.
 *
 * raw_cast can be told to bring up any combination of transports in a single
 * invocation. Setting a port to 0 disables that transport.
 */
data class CliOptions(
    // HTTP/1.1 keep-alive server.
    val httpPort: Int = 53516,
    // Raw TCP frame stream
    val tcpPort: Int = 0,
    // ADB stdout sink mode. When true, no network sockets are opened.
    val stdoutMode: Boolean = false,
    val oneShot: Boolean = false,
    // Default capture parameters used by stdout mode (network transports
    // forward per-request parameters from clients instead).
    val defaultRequest: CaptureRequest = CaptureRequest(),
    val defaultFps: Int = 30,
    // How many successive ports to try if the requested one is occupied.
    // e.g. portRetries=10 means try port, port+1, ..., port+9.
    val portRetries: Int = 10,
) {
    companion object {
        /**
         * Parse the args passed by `app_process`. Accepts these forms:
         *   --key=value
         *   --key value
         *   --bool-flag       (for stdout, oneshot, lz4)
         *
         * Recognised keys: port, tcp, mode, format, fps, lz4,
         * width, height, quality, oneshot, port-retry.
         */
        fun parse(args: Array<String>): CliOptions {
            val map = HashMap<String, String>()
            val flags = HashSet<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (!a.startsWith("--")) { i++; continue }
                val raw = a.substring(2)
                val eq = raw.indexOf('=')
                if (eq >= 0) {
                    map[raw.substring(0, eq).lowercase()] = raw.substring(eq + 1)
                    i++
                } else {
                    val k = raw.lowercase()
                    val next = args.getOrNull(i + 1)
                    // bool flags: stdout, oneshot, lz4
                    if (k in BOOL_FLAGS && (next == null || next.startsWith("--"))) {
                        flags.add(k); i++
                    } else if (next != null) {
                        map[k] = next; i += 2
                    } else { flags.add(k); i++ }
                }
            }

            val mode = map["mode"]?.lowercase()
            val stdoutMode = mode == "stdout" || flags.contains("stdout")
            val unsupported = map.keys.firstOrNull { it !in VALUE_KEYS }
                ?: flags.firstOrNull { it !in BOOL_FLAGS }
            if (unsupported != null) {
                throw IllegalArgumentException("unsupported option '--$unsupported'")
            }

            val httpPort = map["port"]?.toIntOrNull() ?: 53516
            val tcpPort = map["tcp"]?.toIntOrNull() ?: 0
            val portRetries = map["port-retry"]?.toIntOrNull()?.coerceIn(1, 100) ?: 10

            val format = PixelFmt.parse(map["format"])
            val lz4 = (map["compress"]?.lowercase() == "lz4" || flags.contains("lz4")) && format.isRaw
            val width = map["width"]?.toIntOrNull() ?: 0
            val height = map["height"]?.toIntOrNull() ?: 0
            val quality = map["quality"]?.toIntOrNull()?.coerceIn(1, 100) ?: 100
            val fps = map["fps"]?.toIntOrNull()?.coerceIn(0, 120) ?: 30
            val oneShot = flags.contains("oneshot") || (stdoutMode && fps == 0)

            return CliOptions(
                httpPort = if (stdoutMode) 0 else httpPort,
                tcpPort = if (stdoutMode) 0 else tcpPort,
                stdoutMode = stdoutMode,
                oneShot = oneShot,
                defaultRequest = CaptureRequest(width, height, format, lz4, quality),
                defaultFps = fps,
                portRetries = portRetries,
            )
        }

        private val BOOL_FLAGS = setOf("stdout", "oneshot", "lz4")
        private val VALUE_KEYS = setOf(
            "port", "tcp", "mode", "format", "compress", "fps",
            "width", "height", "quality", "port-retry",
        )
    }

    fun summary(): String = buildString {
        append("raw_cast options:\n")
        if (stdoutMode) {
            append("  mode=stdout fps=$defaultFps oneShot=$oneShot ")
            append("format=${defaultRequest.format.name} lz4=${defaultRequest.lz4} ")
            append("size=${defaultRequest.width}x${defaultRequest.height} ")
            append("quality=${defaultRequest.quality}\n")
        } else {
            if (httpPort > 0) append("  HTTP/1.1 port=$httpPort\n")
            if (tcpPort > 0) append("  Raw TCP    port=$tcpPort\n")
        }
    }
}
