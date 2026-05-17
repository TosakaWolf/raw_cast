package ink.mol.raw_cast

/**
 * A produced frame, ready to be wrapped by any transport (HTTP body, raw TCP
 * frame, HTTP chunked stream, stdout).
 *
 *  - [data] holds either RAW pixels (when [format].isRaw) or the compressed
 *    bytes of a PNG/WEBP image. When [lz4] is true, RAW pixels have been
 *    further compressed with LZ4 (payload only, header is not compressed).
 *  - [stride] is the row stride in bytes for raw payloads (0 = tightly packed).
 *
 * The same struct is also used to produce the on-the-wire framed bytes via
 * [FrameMux] when the transport is not a plain HTTP body.
 */
class EncodedFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val format: PixelFmt,
    val lz4: Boolean,
    val stride: Int,
    val seq: Int,
    val timestampMs: Int,
) {
    val flags: Int get() = if (lz4) Frame.FLAG_LZ4 else 0
}
