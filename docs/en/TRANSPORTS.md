# Transports

<p>
  <a href="../zh/TRANSPORTS.md">简体中文</a> ·
  <a href="./TRANSPORTS.md">English</a> ·
  <a href="../ja/TRANSPORTS.md">日本語</a>
</p>

raw_cast primarily uses Raw TCP and ADB stdout. HTTP/1.1 keep-alive is only for browser preview, troubleshooting, and debug streaming.

## Raw TCP

Raw TCP is useful for local programs, OpenCV, or clients that want minimal protocol overhead.

```shell
adb forward tcp:53517 tcp:53517
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main --tcp=53517
```

After connecting, the client writes one ASCII request line:

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

The server writes an 8-byte banner and then continuous RC01 frames. `fps=0` sends one frame and closes the connection.

## ADB stdout

stdout mode does not need port forwarding and is useful for automation and local programs.

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --mode=stdout \
    --format=rgb565 \
    --fps=30 \
    2>/dev/null
```

> **Important: never merge stderr into stdout.** stdout is the binary channel containing the 8-byte banner and continuous RC01 frames; stderr must be discarded or read separately as logs.

## HTTP/1.1 Keep-Alive

HTTP is for debugging only. The default port is 53516:

```shell
adb forward tcp:53516 tcp:53516
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main --port=53516
```

Endpoints:

| Path | Contents |
| --- | --- |
| `/screenshot` | One `rgb565/rgba`, PNG, or WEBP frame; metadata is returned in `X-Frame-*` headers |
| `/preview` | One image frame, PNG by default, suitable for browser viewing |
| `/stream` | `application/x-raw-cast-frames` chunked debug stream; each chunk is one RC01 frame |

The HTTP channel is convenient for browsers and standard-client troubleshooting, but it is not the default channel for high-frequency benchmarks.

## Parameters

Raw TCP, ADB stdout, and the HTTP debug channel share these parameters:

| Parameter | Values | Description |
| --- | --- | --- |
| `format` | `rgb565` `rgba` `png` `webp` | Output format |
| `width` / `height` | integer | `0` means current device size |
| `compress` | `lz4` or `none` | Only applies to `rgb565/rgba` |
| `quality` | `1..100` | WEBP quality |
| `fps` | `0..120` or `1..120` | Stream frame rate; Raw TCP supports `0` for one-shot |

## Recommendations

| Scenario | Recommended transport |
| --- | --- |
| Real-time OpenCV processing | Raw TCP `format=rgb565` |
| Automation pipeline | ADB stdout |
| Browser preview | HTTP `/preview` |
| Standard HTTP client debugging | HTTP `/screenshot` or `/stream` |
