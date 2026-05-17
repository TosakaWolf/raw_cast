# CLI

<p>
  <a href="../zh/CLI.md">简体中文</a> ·
  <a href="../en/CLI.md">English</a> ·
  <a href="./CLI.md">日本語</a>
</p>

`raw_cast` は `app_process` 経由で実行します。引数は `--key=value` または `--key value` 形式です。

## 基本起動

HTTP/1.1 keep-alive のデフォルトポートは 53516 です。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main --port=53516
```

HTTP/1.1 と Raw TCP を同時に有効化する例：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517
```

stdout モード：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --mode=stdout --format=bgra --fps=30 > stream.bin
```

## ポート引数

| 引数 | 既定値 | 説明 |
| --- | ---: | --- |
| `--port=N` | `53516` | HTTP/1.1 keep-alive ポート。`0` で無効 |
| `--tcp=N` | `0` | Raw TCP ポート。`0` で無効 |
| `--mode=stdout` | - | ネットワークポートを開かず、stdout にフレームを書き出す |
| `--port-retry=N` | `10` | ポート使用中に後続ポートを試す回数 |

ネットワークモードでは HTTP または Raw TCP の少なくとも一方が必要です。stdout モードではネットワークポートは無視されます。

## キャプチャ引数

| 引数 | 既定値 | 説明 |
| --- | --- | --- |
| `--format=NAME` | `rgb565` | `rgb565`、`rgba`、`bgra`、`png`、`webp` |
| `--width=N` | `0` | 出力幅。`0` は現在の端末サイズ |
| `--height=N` | `0` | 出力高さ。`0` は現在の端末サイズ |
| `--compress=lz4` | `none` | raw format のみ有効 |
| `--quality=N` | `100` | WEBP 品質、1..100 |
| `--fps=N` | `30` | 連続出力のフレームレート。stdout では `0` が 1 フレーム |
| `--oneshot` | - | stdout で 1 フレーム出力して終了 |

未対応の format は明示的に失敗し、デフォルト format へはフォールバックしません。

## stdout の起動状態

ネットワークモードでは stdout に構造化された状態行を出力します。各行は flush されます。

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

ポートをバインドできない場合：

```text
BIND:HTTP=FAILED
```

ネットワーク transport が 1 つも成功しない場合は `READY=0` を出力して終了します。呼び出し側はこの状態行を読み、実際のポートで `adb forward` を設定してください。

`--mode=stdout` は `PID` / `BIND` / `READY` を出力しません。stdout には binary banner と RC01 frames だけが含まれます。detached 起動で stdout を読めない場合は、固定ポートと `--port-retry=1` を使ってください。

## ブラウザプレビュー

ブラウザで次を開けます：

```text
http://127.0.0.1:53516/preview
```

単一 PNG/WEBP 画像を返します。スクリーンショットとストリーミングには HTTP `/screenshot`、HTTP `/stream`、Raw TCP、stdout を使えます。
