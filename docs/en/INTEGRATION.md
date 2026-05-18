# Integration

<p>
  <a href="../zh/INTEGRATION.md">简体中文</a> ·
  <a href="./INTEGRATION.md">English</a> ·
  <a href="../ja/INTEGRATION.md">日本語</a>
</p>

This page covers host-side integration patterns. Examples assume the APK is already at `/data/local/tmp/raw_cast.apk`.

## Launcher

Network mode must keep the `adb shell` process alive and read startup status from stdout. stderr is log-only and is not part of the port protocol:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --port=53516 --tcp=53517
```

After reading `READY=1`, configure forwards using the actual ports:

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

`--mode=stdout` is different: stdout is the binary RC01 stream and must not be parsed as text status.

## Raw TCP

After connecting to the Raw TCP port, send one ASCII request line:

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

Then read the 8-byte banner and parse continuous RC01 frames as described in [PROTOCOL.md](PROTOCOL.md).

## stdout

stdout mode is useful when the caller directly consumes the binary stream:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --mode=stdout \
    --format=rgb565 \
    --fps=30 \
    2>/dev/null
```

This mode does not print `PID` / `BIND` / `READY` text.
The host should read the banner and RC01 frames from the child process stdout pipe.

> **Important: never merge stderr into stdout.** `2>/dev/null` only drops stderr logs. If logs are needed, read stderr separately.

## HTTP Debug

HTTP is only for browser preview and standard-client debugging. Clients can use `/screenshot`, `/preview`, and `/stream`.

One-shot endpoints:

```text
GET http://127.0.0.1:53516/screenshot?format=png
GET http://127.0.0.1:53516/preview?format=webp&quality=80
```

Streaming endpoint:

```text
GET http://127.0.0.1:53516/stream?format=rgb565&fps=30
```

For `/stream`, the HTTP client usually handles chunked transfer encoding. Application code then reads RC01 frames using the `payload_size` field.
