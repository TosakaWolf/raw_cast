# Protocol

<p>
  <a href="../zh/PROTOCOL.md">简体中文</a> ·
  <a href="../en/PROTOCOL.md">English</a> ·
  <a href="./PROTOCOL.md">日本語</a>
</p>

この文書は raw_cast の RC01 binary frame を定義します。Raw TCP、HTTP `/stream`、ADB stdout は同じ frame header を使うため、クライアントは同じ decoder を再利用できます。

HTTP `/screenshot` と `/preview` は payload を直接返し、メタ情報はレスポンスヘッダーに入ります。HTTP `/stream` には banner はなく、レスポンス body は chunked stream で、各 chunk が完全な RC01 frame です。

## Banner

Raw TCP と ADB stdout は frame sequence の前に 8 バイト banner を書きます。

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       4     protocol_version = 1, little-endian uint32
```

HTTP `/stream` には banner がありません。

## RC01 Frame Header

各 frame は 32 バイト little-endian header と payload で構成されます。

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       1     version = 1
5       1     flags
6       2     format
8       4     seq
12      4     width
16      4     height
20      4     stride
24      4     payload_size
28      4     timestamp_ms
```

`flags`：

| bit | 名前 | 説明 |
| ---: | --- | --- |
| 0 | `LZ4` | payload が LZ4 圧縮。raw format のみ有効 |

`stride` は raw payload の 1 行あたりのバイト数です。画像 format では `0` です。

## Format ids

| id | 名前 | 種類 | bytes/pixel | payload |
| ---: | --- | --- | ---: | --- |
| 1 | `RGB565` | raw | 2 | packed RGB565 |
| 2 | `RGBA8888` | raw | 4 | RGBA channel order |
| 11 | `PNG` | image | - | PNG byte stream |
| 12 | `WEBP` | image | - | WEBP byte stream |

format id `3` は予約済みで未使用です。PNG と WEBP の id は wire compatibility のため再採番しません。

## LZ4

LZ4 は raw payload のみに適用されます。frame header は圧縮されず、`payload_size` は圧縮後の長さです。展開後のサイズは `width * height * bytes_per_pixel` で計算できます。

PNG または WEBP では、サーバーは `compress=lz4` を無視します。

## Raw TCP Request Line

Raw TCP ポートに接続した後、クライアントは ASCII の 1 行を送ります。

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

パラメータは空白区切りです。未知のパラメータは無視されます。未対応 format は接続終了と端末ログへの記録になります。

## HTTP Responses

`/screenshot` と `/preview` は単一 payload とヘッダーを返します。

```text
X-Frame-Width: 1080
X-Frame-Height: 2400
X-Frame-Format: RAW_RGB565
X-Frame-Lz4: 0
X-Frame-Seq: 1
```

`/stream` は次を返します。

```text
Content-Type: application/x-raw-cast-frames
Transfer-Encoding: chunked
```

HTTP クライアントが chunked transfer encoding を処理した後、アプリケーションは body を連続 RC01 frame stream として読みます。
