# Python サンプル

<p>
  <a href="./README.md">简体中文</a> ·
  <a href="./README.en.md">English</a> ·
  <a href="./README.ja.md">日本語</a>
</p>

このディレクトリから実行します。

```shell
cd examples/python
python -m pip install -r requirements.txt
```

`adb` が見つからない場合、サンプルは platform-tools を `examples/platform-tools` に自動ダウンロードします。現在のディレクトリに `raw_cast*.apk` がない場合、raw_cast サンプルは最新 release APK を自動ダウンロードします。DroidCast_raw の viewer/benchmark は必要に応じて `DroidCast_raw.apk` を自動ダウンロードします。

エミュレーターの ADB を TCP 経由で使う場合は、アドレスを明示します。

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

APK を手動指定する場合:

```shell
python droidcast_raw_argb8888_bench.py --apk DroidCast_raw.apk --adb-address 127.0.0.1:16384
```
