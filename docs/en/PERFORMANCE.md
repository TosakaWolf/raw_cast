# Performance

<p>
  <a href="../zh/PERFORMANCE.md">简体中文</a> ·
  <a href="./PERFORMANCE.md">English</a> ·
  <a href="../ja/PERFORMANCE.md">日本語</a>
</p>

raw_cast latency mainly comes from capture, pixel conversion, encoding or compression, and transport. Actual numbers depend on device, resolution, Android version, and USB link quality.

## Format Choice

| Format | Per-frame payload | CPU cost | Best for |
| --- | --- | --- | --- |
| `rgb565` | Half of `rgba`, still full-frame unencoded pixels | Low | Real-time capture, CV, inference |
| `rgba` | 2x `rgb565`, still full-frame unencoded pixels | Low | Full 4-channel pixels |
| `png` | Depends on screen content, usually smaller than unencoded pixels | High | Lossless one-shot capture and comparisons |
| `webp` | Depends on quality and screen content | Medium | Browser preview and compressed one-shot capture |
| `rgb565` + LZ4 | Compressed size depends on screen changes | Medium | Real-time `rgb565/rgba` streams when the ADB link is under pressure |

`compress=lz4` only applies to `rgb565/rgba`. PNG and WEBP are already compressed image formats.

## Transport Choice

| Transport | Protocol overhead | Strength | Recommendation |
| --- | --- | --- | --- |
| ADB stdout | Lowest | No port forwarding | Automation and local programs |
| Raw TCP | Low | Simple, stable, easy to parse | OpenCV and inference pipelines |
| HTTP/1.1 keep-alive | Medium | Standard clients, browser preview, easy integration | App integration, one-shot screenshots, long-lived streams |

HTTP `/stream` uses a persistent connection and chunked response, so it does not reconnect per frame. Compared with Raw TCP it adds HTTP chunk boundaries and headers, but it is much easier to integrate with standard clients.

## Benchmark Reference

The following reference run used MuMu emulator, Android 12, 1280x720, decoding `rgb565` frames into Mat for 300 samples. Each unencoded `rgb565` payload was about 1.76 MB, and the converted Mat was about 2.64 MB. Treat these numbers as transport and compression comparisons within that environment, not universal device results.

| Combo | First frame | p50 | p95 | Effective fps | Observation |
| --- | ---: | ---: | ---: | ---: | --- |
| stdout + `rgb565` | 611 ms | 78 ms | 108 ms | 12.37 | Simplest startup path, but uncompressed large frames can be limited by stdout and ADB pipe throughput |
| stdout + `rgb565` + LZ4 | 383 ms | 16 ms | 26 ms | 58.87 | Much higher throughput, useful for no-port automation and fallback paths |
| Raw TCP + `rgb565` | 102 ms | 44 ms | 63 ms | 21.89 | More stable than stdout when uncompressed, with much lower first-frame latency |
| Raw TCP + `rgb565` + LZ4 | 49 ms | 17 ms | 23 ms | 57.89 | Low and stable latency, the preferred combo for real-time Mat pipelines in this environment |

Takeaway: `rgb565` + LZ4 greatly reduces transfer pressure in this emulator test. Raw TCP is the better default for long-running real-time streams; stdout remains useful for single-channel automation, one-shot capture, or port-unavailable fallback.

## Recommended Combos

| Scenario | Parameters |
| --- | --- |
| Real-time CV or inference | Raw TCP, `format=rgb565`, convert color on the host side |
| `rgb565/rgba` stream under ADB link pressure | Raw TCP, `format=rgb565&compress=lz4` |
| Standard client streaming | HTTP, `/stream?format=rgb565&fps=30` |
| Lossless screenshot | HTTP, `/screenshot?format=png` |
| Browser preview | `/preview?format=webp&quality=80` |
| Automation one-shot | stdout, `--format=rgb565 --oneshot` |

## Tuning Order

1. Check whether the requested capture size is necessary; lowering resolution usually helps most.
2. For CV workloads, prefer `rgb565` to reduce device-side work and transfer size, then convert to the required matrix format on the host.
3. If full-frame `rgb565/rgba` payloads are too heavy, try `rgb565&compress=lz4`.
4. Prefer HTTP for standard clients; prefer Raw TCP or stdout for maximum single-stream throughput.
5. Prefer WEBP for manual previews.

## Notes

- `quality=100` may use lossless WEBP on supported platforms, increasing size and encode time.
- PNG is lossless but expensive to encode, so it is not ideal for high-frame-rate preview.
- stdout is a binary stream; callers must not mix logs into stdout.
- Android private APIs can change across OS versions, so performance and availability should be measured on target devices.
