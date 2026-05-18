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

USB 端末、または接続済み端末が 1 台だけの場合は、下のコマンドをそのまま実行できます。複数端末がある場合は USB 端末を `--serial SERIAL` で指定します。エミュレーターの ADB を TCP 経由で使う場合は `--adb-address HOST:PORT` を指定します。

Raw TCP サンプルは接続後に request line を送信します。stdout サンプルは request line を送信せず、format、FPS、compression は `app_process` の起動オプションで指定します。stdout には binary RC01 banner と frame data だけが流れます。

## raw_cast Raw TCP RGB565 + LZ4

```shell
python raw_tcp_rgb565_lz4_viewer.py
```

## raw_cast Raw TCP RGB565

```shell
python raw_tcp_rgb565_viewer.py
```

## raw_cast Raw TCP RGBA + LZ4

```shell
python raw_tcp_rgba_lz4_viewer.py
```

## raw_cast Raw TCP RGBA

```shell
python raw_tcp_rgba_viewer.py
```

## raw_cast ADB stdout RGB565 + LZ4

```shell
python stdout_rgb565_lz4_viewer.py
```

## raw_cast ADB stdout RGB565

```shell
python stdout_rgb565_viewer.py
```

## raw_cast ADB stdout RGBA + LZ4

```shell
python stdout_rgba_lz4_viewer.py
```

## raw_cast ADB stdout RGBA

```shell
python stdout_rgba_viewer.py
```

## DroidCast_raw ARGB_8888 Viewer + Benchmark

```shell
python droidcast_raw_argb8888_bench.py
```

APK を手動指定する場合:

```shell
python droidcast_raw_argb8888_bench.py --apk DroidCast_raw.apk
```

エミュレーター TCP ADB の例:

```shell
python raw_tcp_rgb565_lz4_viewer.py --adb-address 127.0.0.1:16384
```

複数 USB 端末の例:

```shell
python raw_tcp_rgb565_lz4_viewer.py --serial DEVICE_SERIAL
```
