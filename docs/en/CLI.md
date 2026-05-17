# CLI

<p>
  <a href="../zh/CLI.md">简体中文</a> ·
  <a href="./CLI.md">English</a> ·
  <a href="../ja/CLI.md">日本語</a>
</p>

`raw_cast` runs through `app_process`. Options use either `--key=value` or `--key value`.

## Basic Launch

The default HTTP/1.1 keep-alive port is 53516:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main --port=53516
```

Start HTTP/1.1 and Raw TCP together:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517
```

stdout mode:

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --mode=stdout --format=bgra --fps=30 > stream.bin
```

## Port Options

| Option | Default | Description |
| --- | ---: | --- |
| `--port=N` | `53516` | HTTP/1.1 keep-alive port; `0` disables it |
| `--tcp=N` | `0` | Raw TCP port; `0` disables it |
| `--mode=stdout` | - | Do not open network sockets; write frames to stdout |
| `--port-retry=N` | `10` | Number of successive ports to try when the requested port is busy |

Network mode must enable HTTP or Raw TCP. stdout mode ignores network ports.

## Capture Options

| Option | Default | Description |
| --- | --- | --- |
| `--format=NAME` | `rgb565` | `rgb565`, `rgba`, `bgra`, `png`, `webp` |
| `--width=N` | `0` | Output width; `0` uses the current device size |
| `--height=N` | `0` | Output height; `0` uses the current device size |
| `--compress=lz4` | `none` | Only applies to raw formats |
| `--quality=N` | `100` | WEBP quality, 1..100 |
| `--fps=N` | `30` | Streaming frame rate; in stdout mode `0` means one frame |
| `--oneshot` | - | In stdout mode, emit one frame and exit |

Unsupported formats fail explicitly instead of falling back to the default.

## stdout Status

In network mode, startup status is printed to stdout. Each line is flushed:

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

If a port cannot be bound:

```text
BIND:HTTP=FAILED
```

If no network transport is bound successfully, the process prints `READY=0` and exits. The caller should read these lines and configure `adb forward` using the actual ports.

`--mode=stdout` does not print `PID` / `BIND` / `READY`; stdout contains only the binary banner and RC01 frames. Detached launchers that cannot read stdout should use fixed ports with `--port-retry=1`.

## Browser Preview

Open this URL in a browser:

```text
http://127.0.0.1:53516/preview
```

It returns a single PNG/WEBP image. Screenshots and streams are available through HTTP `/screenshot`, HTTP `/stream`, Raw TCP, or stdout.
