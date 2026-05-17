# Troubleshooting

<p>
  <a href="../zh/TROUBLESHOOTING.md">简体中文</a> ·
  <a href="../en/TROUBLESHOOTING.md">English</a> ·
  <a href="./TROUBLESHOOTING.md">日本語</a>
</p>

## プロセスが起動しない

まず stdout/stderr を null device にリダイレクトせず、前面で実行します。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main --port=53516 --tcp=53517
```

よくある原因：

| 現象 | 確認 |
| --- | --- |
| `ClassNotFoundException` | APK パスと `CLASSPATH=` を確認 |
| stdout に `PID` / `BIND` / `READY` がない | stderr と logcat で早期 crash を確認 |
| `BIND:HTTP=FAILED` | ポートが使用中。ポートを変えるか `--port-retry` を増やす |
| `no transports enabled` | HTTP と Raw TCP が無効で、`--mode=stdout` も使っていない |

## stdout に起動状態が出ない

ネットワークモードでは stdout を読む必要があります。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517
```

期待される出力：

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

何も出ない場合：

1. 前面で起動し、stdout と stderr の両方を表示します。
2. `adb shell logcat -d | grep raw_cast` で例外を確認します。
3. APK が `CLASSPATH` の場所に push 済みか確認します。
4. HTTP または Raw TCP が有効か、または `--mode=stdout` を使っているか確認します。
5. detached 起動が必要で stdout を読めない場合は、固定ポートと `--port-retry=1` を使います。

## HTTP に接続できない

forward と実際の bound port を確認します。

```shell
adb forward --list
```

stdout の `BIND:HTTP=` が要求ポートと異なる実ポートを示す場合は、その実ポートを forward してください。

## ブラウザ preview は動くが stream が失敗する

ブラウザ preview：

```text
http://127.0.0.1:53516/preview
```

stream では `/stream` を使い、レスポンス body を RC01 frames として解析します。標準 HTTP client は通常 chunked transfer encoding を自動で処理するため、アプリ側は `payload_size` で frame を切り出します。

## Format エラー

対応 format：

```text
rgb565, rgba, png, webp
```

未対応 format は 400 を返すか端末ログに記録されます。デフォルトへ静かに戻ることはありません。

## 黒画面またはサイズ不一致

考えられる原因：

1. 対象端末が SurfaceControl screenshot API を制限している。
2. 現在の画面が protected content である。
3. 回転状態や外部 display の設定でサイズが変わっている。

ブラウザ preview を試してください。

```text
http://127.0.0.1:53516/preview?format=webp&quality=80
```

preview も異常な場合は、logcat の `ScreenCaptor` と `raw_cast` ログを確認します。

## フレームレートが低い

確認順：

1. `width=` と `height=` で解像度を下げます。
2. CV では Raw TCP + `format=rgb565` を使い、BGR/RGB matrix への変換はホスト側で行います。
3. 単一 stream の最大性能では Raw TCP または stdout を使います。
4. 全フレームの `rgb565/rgba` payload が重い場合は `format=rgb565&compress=lz4` を試します。
5. ホスト側の消費速度が十分か確認します。

## stdout stream を解析できない

stdout は純粋な binary stream です。ホスト側の client program が pipe から直接読み取ってください。ファイルへの保存は一時的なデバッグ用途に留めます。ログは stderr に出ます。Windows shell では改行を書き換えるツールを避けてください。

## 現在の機能を確認する

コードと文書を検索します。

```shell
rg -n "format=|BIND:|Raw TCP|HTTP/1.1|stdout|preview" README.md docs app/src/main/java
```
