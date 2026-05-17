# Performance

<p>
  <a href="../zh/PERFORMANCE.md">简体中文</a> ·
  <a href="../en/PERFORMANCE.md">English</a> ·
  <a href="./PERFORMANCE.md">日本語</a>
</p>

raw_cast の遅延は主に capture、pixel conversion、encoding/compression、transport から発生します。実際の値は端末、解像度、Android バージョン、USB link によって変わります。

## Format Choice

| Format | 1 フレーム payload | CPU cost | 用途 |
| --- | --- | --- | --- |
| `rgb565` | `rgba` の半分ですが、全フレームの未エンコード pixels です | 低 | リアルタイム capture、CV、推論 |
| `rgba` | `rgb565` の 2 倍で、全フレームの未エンコード pixels です | 低 | 完全な 4 channel pixels |
| `png` | 画面内容に依存し、通常は未エンコード pixels より小さくなります | 高 | 無劣化の単一フレーム、比較テスト |
| `webp` | quality と画面内容に依存します | 中 | ブラウザプレビュー、圧縮された単一フレーム |
| `rgb565` + LZ4 | 圧縮後のサイズは画面変化に依存します | 中 | ADB link に負荷がある場合のリアルタイム `rgb565/rgba` stream |

`compress=lz4` は `rgb565/rgba` のみに適用されます。PNG と WEBP はすでに圧縮画像です。

## Transport Choice

| Transport | Protocol overhead | 強み | 推奨 |
| --- | --- | --- | --- |
| Raw TCP | 低 | 単純、安定、解析しやすい | OpenCV、推論 pipeline |
| ADB stdout | 最低 | ポート転送なし | 自動化、ローカルプログラム |
| HTTP/1.1 keep-alive | 中 | 標準 client、ブラウザ preview、連携しやすい | ブラウザ preview と debug stream |

HTTP `/stream` は persistent connection と chunked response を使うため、フレームごとに再接続しません。Raw TCP より HTTP chunk 境界と header の分だけ余分です。debug や標準 client での調査に便利ですが、高頻度 benchmark のデフォルト channel ではありません。

## ポート・圧縮・ベンチマーク

- 要求した端末側ポートが使用中で `--port-retry` が後続ポートへ fallback した場合は、stdout の実ポートへ forward してください。たとえば `BIND:TCP=53519` の場合は `adb forward tcp:53517 tcp:53519` を使います。
- 固定ポートで手動デバッグするだけなら、先に `adb forward tcp:53517 tcp:53517` を実行し、`--port-retry=1` で起動すると端末側ポートの自動変更を避けられます。
- 全フレームの `rgb565/rgba` payload が ADB link に負荷をかける場合は、同じ Raw TCP 接続を保持し、request 行を `format=rgb565 fps=120 width=0 height=0 compress=lz4` に変更します。
- Benchmark では `transport + pixel format + compression` の各組み合わせごとに stream を 1 回だけ初期化し、warmup 後に連続フレーム取得を測定します。初回フレーム latency は別に記録します。
- 各組み合わせの測定後は reader、forward、remote process を停止して、次の測定に影響しないようにします。

## ベンチマーク参考

以下は MuMu エミュレーター、Android 12、1280x720 で、`rgb565` を Mat に変換した 200 フレーム、失敗 0 の参考値です。未エンコードの `rgb565` payload は 1 フレーム約 1.76 MB、変換後の Mat は約 2.64 MB です。この値は同じ環境で transport と compression を比較するためのもので、すべての実機で同じ結果になるわけではありません。

| 組み合わせ | 初回フレーム | p50 | p95 | 実効 fps | 傾向 |
| --- | ---: | ---: | ---: | ---: | --- |
| stdout + `rgb565` | 101 ms | 81 ms | 114 ms | 11.93 | 起動は単純ですが、未圧縮の大きなフレームでは stdout/ADB pipe が詰まりやすいです |
| stdout + `rgb565` + LZ4 | 12 ms | 13 ms | 21 ms | 68.52 | throughput が大きく改善し、ポートを使わない自動化や fallback に向きます |
| Raw TCP + `rgb565` | 50 ms | 44 ms | 59 ms | 22.60 | 未圧縮では stdout より安定し、初回フレームも短くなります |
| Raw TCP + `rgb565` + LZ4 | 18 ms | 12 ms | 19 ms | 73.52 | latency が低く安定しており、この環境ではリアルタイム Mat pipeline の優先候補です |
| MuMu render baseline | 7 ms | 7 ms | 8 ms | 133.30 | エミュレーター内部の render baseline で、raw_cast capture と ADB 転送 cost は含みません |

まとめると、この emulator 環境では `rgb565` + LZ4 が転送負荷を大きく下げます。長時間のリアルタイム stream では Raw TCP + LZ4 を優先し、stdout + LZ4 は単一 channel の自動化、one-shot、ポートが使えない場合の fallback として使うのが向いています。

## Recommended Combos

| 場面 | パラメータ |
| --- | --- |
| CV / 推論のリアルタイム処理 | Raw TCP、`format=rgb565`、ホスト側で色変換 |
| ADB link に負荷がある `rgb565/rgba` stream | Raw TCP、`format=rgb565&compress=lz4` |
| 標準 client での debug stream | HTTP、`/stream?format=rgb565&fps=30` |
| debug 用の無劣化スクリーンショット | HTTP、`/screenshot?format=png` |
| ブラウザ preview | `/preview?format=webp&quality=80` |
| 自動化 one-shot | stdout、`--format=rgb565 --oneshot` |

## Tuning Order

1. 必要な capture size か確認します。解像度を下げるのが最も効くことが多いです。
2. CV では `rgb565` を優先して端末側の処理と転送量を抑え、必要な matrix format へはホスト側で変換します。
3. 全フレームの `rgb565/rgba` payload が重い場合は `rgb565&compress=lz4` を試します。
4. 高頻度 stream では Raw TCP、次に stdout を優先します。HTTP は debug とブラウザ preview 用です。
5. 手動 preview では WEBP を優先します。

## Notes

- `quality=100` は対応 platform で lossless WEBP になり、サイズと encode 時間が増える場合があります。
- PNG は無劣化ですが encode cost が高く、高フレームレート preview には向きません。
- stdout は binary stream です。呼び出し側は stderr logs を stdout に混ぜないでください。
- Android private API は OS バージョンで変わる可能性があるため、対象端末での実測が必要です。
