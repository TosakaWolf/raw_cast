# Python Examples

<p>
  <a href="./README.md">简体中文</a> ·
  <a href="./README.en.md">English</a> ·
  <a href="./README.ja.md">日本語</a>
</p>

Run commands from this directory:

```shell
cd examples/python
python -m pip install -r requirements.txt
```

If `adb` is not found, the examples auto-download platform-tools into `examples/platform-tools`. If no `raw_cast*.apk` is found, raw_cast examples auto-download the latest release APK. The DroidCast_raw viewer/benchmark auto-downloads `DroidCast_raw.apk` when needed.

For emulator ADB over TCP, pass the address explicitly:

```shell
--adb-address 127.0.0.1:16384
```

## raw_cast Raw TCP RGB565 + LZ4

```shell
python raw_tcp_rgb565_lz4_viewer.py --adb-address 127.0.0.1:16384
```

## raw_cast Raw TCP RGB565

```shell
python raw_tcp_rgb565_viewer.py --adb-address 127.0.0.1:16384
```

## raw_cast Raw TCP RGBA + LZ4

```shell
python raw_tcp_rgba_lz4_viewer.py --adb-address 127.0.0.1:16384
```

## raw_cast Raw TCP RGBA

```shell
python raw_tcp_rgba_viewer.py --adb-address 127.0.0.1:16384
```

## DroidCast_raw ARGB_8888 Viewer + Benchmark

```shell
python droidcast_raw_argb8888_bench.py --adb-address 127.0.0.1:16384
```

Optional manual APK path:

```shell
python droidcast_raw_argb8888_bench.py --apk DroidCast_raw.apk --adb-address 127.0.0.1:16384
```
