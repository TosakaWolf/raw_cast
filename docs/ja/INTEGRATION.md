# Integration

<p>
  <a href="../zh/INTEGRATION.md">简体中文</a> ·
  <a href="../en/INTEGRATION.md">English</a> ·
  <a href="./INTEGRATION.md">日本語</a>
</p>

このページは host 側の連携方法を示します。例は APK が `/data/local/tmp/raw_cast.apk` にある前提です。

## Launcher

ネットワークモードでは `adb shell` プロセスを維持し、stdout から起動状態を読みます。stderr はログ専用で、ポートプロトコルには含まれません。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517
```

`READY=1` を読んだ後、実際のポートに対して forward を設定します。

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

`--mode=stdout` は別モードです。stdout は binary RC01 stream なので、テキスト状態として解析してはいけません。

## HTTP

標準 HTTP クライアントで `/screenshot`、`/preview`、`/stream` を利用できます。

単一フレーム endpoint：

```text
GET http://127.0.0.1:53516/screenshot?format=png
GET http://127.0.0.1:53516/preview?format=webp&quality=80
```

stream endpoint：

```text
GET http://127.0.0.1:53516/stream?format=rgb565&fps=30
```

`/stream` では HTTP クライアントが通常 chunked transfer encoding を処理します。アプリケーション側は RC01 の `payload_size` を使って frame を切り出します。

## Raw TCP

Raw TCP ポートに接続した後、ASCII の 1 行を送ります。

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

その後 8 バイト banner を読み、[PROTOCOL.md](PROTOCOL.md) に従って連続 RC01 frames を解析します。

## stdout

stdout モードは呼び出し側が binary stream を直接消費する場合に向いています。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --mode=stdout --format=rgb565 --fps=30 2>/dev/null
```

このモードは `PID` / `BIND` / `READY` テキストを出力しません。
ホスト側は子プロセスの stdout pipe から banner と RC01 frames を読み取ります。`2>/dev/null` は stderr logs だけを捨てます。
