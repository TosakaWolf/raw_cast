# Performance

<p>
  <a href="../zh/PERFORMANCE.md">简体中文</a> ·
  <a href="../en/PERFORMANCE.md">English</a> ·
  <a href="./PERFORMANCE.md">日本語</a>
</p>

raw_cast の遅延は主に capture、pixel conversion、encoding/compression、transport から発生します。実際の値は端末、解像度、Android バージョン、USB link によって変わります。

## Format Choice

| Format | 帯域 | CPU cost | 用途 |
| --- | --- | --- | --- |
| `rgb565` | 低 | 低 | 低帯域の raw data |
| `png` | 低から中 | 高 | 無劣化の単一フレーム、比較テスト |
| `webp` | 低 | 中 | ブラウザプレビュー、低帯域の単一フレーム |
| raw + LZ4 | 中 | 中 | 弱い link でのリアルタイム raw stream |

`compress=lz4` は raw format のみに適用されます。PNG と WEBP はすでに圧縮画像です。

## Transport Choice

| Transport | Protocol overhead | 強み | 推奨 |
| --- | --- | --- | --- |
| ADB stdout | 最低 | ポート転送なし | 自動化、ローカルプログラム |
| Raw TCP | 低 | 単純、安定、解析しやすい | OpenCV、推論 pipeline |
| HTTP/1.1 keep-alive | 中 | 標準 client、ブラウザ preview、連携しやすい | アプリ連携、単一フレーム、長時間 stream |

HTTP `/stream` は persistent connection と chunked response を使うため、フレームごとに再接続しません。Raw TCP より HTTP chunk 境界と header の分だけ余分ですが、標準 client との連携が簡単です。

## Recommended Combos

| 場面 | パラメータ |
| --- | --- |
| CV / 推論のリアルタイム処理 | Raw TCP、`format=rgb565`、ホスト側で色変換 |
| 弱い link の raw stream | Raw TCP、`format=rgb565&compress=lz4` |
| 標準 client の stream | HTTP、`/stream?format=rgb565&fps=30` |
| 無劣化スクリーンショット | HTTP、`/screenshot?format=png` |
| ブラウザ preview | `/preview?format=webp&quality=80` |
| 自動化 one-shot | stdout、`--format=rgb565 --oneshot` |

## Tuning Order

1. 必要な capture size か確認します。解像度を下げるのが最も効くことが多いです。
2. CV では `rgb565` を優先して端末側の処理と転送量を抑え、必要な matrix format へはホスト側で変換します。
3. 帯域が足りない場合は `rgb565` または raw + LZ4 を試します。
4. 標準 client では HTTP、単一 stream の最大 throughput では Raw TCP または stdout を優先します。
5. 手動 preview では WEBP を優先します。

## Notes

- `quality=100` は対応 platform で lossless WEBP になり、サイズと encode 時間が増える場合があります。
- PNG は無劣化ですが encode cost が高く、高フレームレート preview には向きません。
- stdout は binary stream です。呼び出し側は stdout にログを混ぜないでください。
- Android private API は OS バージョンで変わる可能性があるため、対象端末での実測が必要です。
