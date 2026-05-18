# Python 示例

<p>
  <a href="./README.md">简体中文</a> ·
  <a href="./README.en.md">English</a> ·
  <a href="./README.ja.md">日本語</a>
</p>

从该目录运行：

```shell
cd examples/python
python -m pip install -r requirements.txt
```

如果找不到 `adb`，示例会自动下载 platform-tools 到 `examples/platform-tools`。如果当前目录没有 `raw_cast*.apk`，raw_cast 示例会自动下载最新 release APK。DroidCast_raw 查看/压测示例会在需要时自动下载 `DroidCast_raw.apk`。

模拟器通过 TCP 暴露 ADB 时，显式传入地址：

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

## DroidCast_raw ARGB_8888 查看 + 压测

```shell
python droidcast_raw_argb8888_bench.py --adb-address 127.0.0.1:16384
```

手动指定 APK：

```shell
python droidcast_raw_argb8888_bench.py --apk DroidCast_raw.apk --adb-address 127.0.0.1:16384
```
