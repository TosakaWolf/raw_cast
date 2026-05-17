# Protocol

<p>
  <a href="../zh/PROTOCOL.md">简体中文</a> ·
  <a href="./PROTOCOL.md">English</a> ·
  <a href="../ja/PROTOCOL.md">日本語</a>
</p>

This document defines the raw_cast RC01 binary frame. Raw TCP, HTTP `/stream`, and ADB stdout use the same frame header, so clients can share one decoder.

HTTP `/screenshot` and `/preview` return the payload directly; metadata is provided in response headers. HTTP `/stream` has no banner. Its body is a chunked stream where each chunk is one complete RC01 frame.

## Banner

Raw TCP and ADB stdout write an 8-byte banner before the frame sequence:

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       4     protocol_version = 1, little-endian uint32
```

HTTP `/stream` does not have a banner.

## RC01 Frame Header

Each frame is a 32-byte little-endian header followed by payload bytes:

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       1     version = 1
5       1     flags
6       2     format
8       4     seq
12      4     width
16      4     height
20      4     stride
24      4     payload_size
28      4     timestamp_ms
```

`flags`:

| bit | Name | Description |
| ---: | --- | --- |
| 0 | `LZ4` | Payload is LZ4-compressed; valid only for raw formats |

`stride` is the row stride in bytes for raw payloads. Image formats use `0`.

## Format ids

| id | Name | Type | Bytes/pixel | Payload |
| ---: | --- | --- | ---: | --- |
| 1 | `RGB565` | raw | 2 | Packed RGB565 |
| 2 | `RGBA8888` | raw | 4 | RGBA channel order |
| 3 | `BGRA8888` | raw | 4 | BGRA channel order |
| 11 | `PNG` | image | - | PNG byte stream |
| 12 | `WEBP` | image | - | WEBP byte stream |

PNG and WEBP ids are not renumbered to preserve wire compatibility.

## LZ4

LZ4 applies only to raw payloads. The frame header is not compressed, and `payload_size` is the compressed length. Clients can compute the decompressed size as `width * height * bytes_per_pixel`.

For PNG or WEBP, the server ignores `compress=lz4`.

## Raw TCP Request Line

After connecting to the Raw TCP port, the client sends one ASCII line:

```text
format=bgra fps=30 width=0 height=0 compress=none
```

Parameters are space-separated. Unknown parameters are ignored. Unsupported formats end the connection and are logged on the device.

## HTTP Responses

`/screenshot` and `/preview` return one payload with headers such as:

```text
X-Frame-Width: 1080
X-Frame-Height: 2400
X-Frame-Format: RAW_BGRA8888
X-Frame-Lz4: 0
X-Frame-Seq: 1
```

`/stream` returns:

```text
Content-Type: application/x-raw-cast-frames
Transfer-Encoding: chunked
```

After the HTTP client decodes chunked transfer encoding, the application reads the body as a continuous RC01 frame stream.
