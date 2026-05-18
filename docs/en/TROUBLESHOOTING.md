# Troubleshooting

<p>
  <a href="../zh/TROUBLESHOOTING.md">简体中文</a> ·
  <a href="./TROUBLESHOOTING.md">English</a> ·
  <a href="../ja/TROUBLESHOOTING.md">日本語</a>
</p>

## Process Does Not Start

First, do not redirect stdout/stderr to a null device. Run it in the foreground:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main --port=53516 --tcp=53517
```

Common causes:

| Symptom | Check |
| --- | --- |
| `ClassNotFoundException` | Confirm the APK path and `CLASSPATH=` value |
| stdout has no `PID` / `BIND` / `READY` | Check stderr and logcat for an early crash |
| `BIND:HTTP=FAILED` | Port is occupied; change the port or increase `--port-retry` |
| `no transports enabled` | Raw TCP and the HTTP debug port are both disabled and `--mode=stdout` was not used |

## No Startup Status on stdout

Network mode requires reading stdout:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --port=53516 --tcp=53517
```

Expected output:

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

If nothing is printed:

1. Start in the foreground and keep both stdout and stderr visible.
2. Run `adb shell logcat -d | grep raw_cast` to inspect exceptions.
3. Confirm the APK was pushed to the path used by `CLASSPATH`.
4. Confirm Raw TCP or the HTTP debug port is enabled, or use `--mode=stdout`.
5. If detached launch is required and stdout cannot be read, use fixed ports with `--port-retry=1`.

## Cannot Connect to HTTP

Check forwarding and the actual bound port:

```shell
adb forward --list
```

If stdout `BIND:HTTP=` shows a different actual port, forward that actual port.

## Browser Preview Works but Streaming Fails

The browser preview is:

```text
http://127.0.0.1:53516/preview
```

For streaming, use `/stream` and parse the response body as RC01 frames. Standard HTTP clients usually decode chunked transfer encoding automatically; application code then splits frames using `payload_size`.

## Format Errors

Supported formats are:

```text
rgb565, rgba, png, webp
```

Unsupported formats return 400 or are logged on the device. They do not silently fall back to the default.

## Black Screen or Wrong Size

Possible causes:

1. The target device restricts the SurfaceControl screenshot API.
2. The current screen contains protected content.
3. Rotation state or external display configuration changes the size.

Try browser preview:

```text
http://127.0.0.1:53516/preview?format=webp&quality=80
```

If preview is also wrong, inspect `ScreenCaptor` and `raw_cast` logs in logcat.

## Frame Rate Is Too Low

Check in this order:

1. Reduce resolution with `width=` and `height=`.
2. For CV workloads, use Raw TCP + `format=rgb565` and convert to BGR/RGB matrices on the host.
3. For maximum single-stream throughput, use Raw TCP or stdout.
4. If full-frame `rgb565/rgba` payloads are too heavy, try `format=rgb565&compress=lz4`.
5. Confirm the host-side consumer is fast enough.

## stdout Stream Cannot Be Parsed

stdout is a pure binary stream and should be read directly from the host-side client process pipe. Write it to a file only for temporary debugging. Logs are on stderr. On Windows shells, avoid tools that rewrite line endings.

## Confirm Current Capabilities

Search the code and docs:

```shell
rg -n "format=|BIND:|Raw TCP|HTTP/1.1|stdout|preview" README.md docs app/src/main/java
```
