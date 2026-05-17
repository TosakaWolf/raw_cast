# Transports

<p>
  <a href="../zh/TRANSPORTS.md">简体中文</a> ·
  <a href="../en/TRANSPORTS.md">English</a> ·
  <a href="./TRANSPORTS.md">日本語</a>
</p>

raw_cast の正式な転送方式は HTTP/1.1 keep-alive、Raw TCP、ADB stdout です。

## HTTP/1.1 Keep-Alive

HTTP がデフォルトの入口です。デフォルトポートは 53516 です。

```shell
adb forward tcp:53516 tcp:53516
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main --port=53516
```

エンドポイント：

| パス | 内容 |
| --- | --- |
| `/screenshot` | raw、PNG、WEBP の単一フレーム。メタ情報は `X-Frame-*` ヘッダー |
| `/preview` | 単一画像フレーム。デフォルトは PNG。ブラウザ確認向け |
| `/stream` | `application/x-raw-cast-frames` の chunked stream。各 chunk が 1 つの RC01 frame |

## Raw TCP

Raw TCP はローカルプログラム、OpenCV、低オーバーヘッドを求めるクライアント向けです。

```shell
adb forward tcp:53517 tcp:53517
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main --tcp=53517
```

接続後、クライアントは ASCII のリクエスト行を 1 行送ります。

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

サーバーは 8 バイト banner の後、RC01 frames を連続して返します。`fps=0` は 1 フレームだけ送って接続を閉じます。

## ADB stdout

stdout モードはポート転送が不要で、自動化やローカルプログラムに向いています。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --mode=stdout --format=rgb565 --fps=30 2>/dev/null
```

ホスト側は `adb shell` 子プロセスの stdout pipe を直接読み取ります。出力は 8 バイト banner + 連続 RC01 frames で、stderr はログ専用です。

## パラメータ

すべての正式 transport は次のパラメータを共有します。

| パラメータ | 値 | 説明 |
| --- | --- | --- |
| `format` | `rgb565` `rgba` `png` `webp` | 出力形式 |
| `width` / `height` | 整数 | `0` は現在の端末サイズ |
| `compress` | `lz4` または `none` | raw format のみ有効 |
| `quality` | `1..100` | WEBP 品質 |
| `fps` | `0..120` または `1..120` | 連続 stream のフレームレート。Raw TCP は `0` の one-shot に対応 |

## 選び方

| 場面 | 推奨 |
| --- | --- |
| ブラウザで確認 | HTTP `/preview` |
| 標準 HTTP クライアント連携 | HTTP `/screenshot` または `/stream` |
| OpenCV リアルタイム処理 | Raw TCP `format=rgb565` |
| 自動化パイプライン | ADB stdout |
