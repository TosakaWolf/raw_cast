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
| Raw TCP | Low | Simple, stable, easy to parse | OpenCV and inference pipelines |
| ADB stdout | Lowest | No port forwarding | Automation and local programs |
| HTTP/1.1 keep-alive | Medium | Standard clients, browser preview, easy integration | Browser preview and debug streaming |

HTTP `/stream` uses a persistent connection and chunked response, so it does not reconnect per frame. Compared with Raw TCP it adds HTTP chunk boundaries and headers. It is useful for debugging and standard-client troubleshooting, but it is not the default channel for high-frequency benchmarks.

## Ports, Compression, And Benchmarks

- If the requested device port is busy and `--port-retry` falls back to a later port, forward to the actual port printed on stdout. For example, when stdout says `BIND:TCP=53519`, use `adb forward tcp:53517 tcp:53519`.
- For manual debugging with a fixed port, run `adb forward tcp:53517 tcp:53517` first and start with `--port-retry=1` to avoid automatic device-side port changes.
- If full-frame `rgb565/rgba` payloads put pressure on the ADB link, keep the same Raw TCP connection and switch the request line to `format=rgb565 fps=120 width=0 height=0 compress=lz4`.
- For benchmarks, initialize each `transport + pixel format + compression` stream once, warm it up, then measure continuous frame reads. Track first-frame latency separately.
- Stop the reader, forward, and remote process after each combination so it does not affect the next test.

## Recommended Combos

| Scenario | Parameters |
| --- | --- |
| Real-time CV or inference | Raw TCP, `format=rgb565`, convert color on the host side |
| `rgb565/rgba` stream under ADB link pressure | Raw TCP, `format=rgb565&compress=lz4` |
| Standard-client debug streaming | HTTP, `/stream?format=rgb565&fps=30` |
| Debug lossless screenshot | HTTP, `/screenshot?format=png` |
| Browser preview | `/preview?format=webp&quality=80` |
| Automation one-shot | stdout, `--format=rgb565 --oneshot` |

## Tuning Order

1. Check whether the requested capture size is necessary; lowering resolution usually helps most.
2. For CV workloads, prefer `rgb565` to reduce device-side work and transfer size, then convert to the required matrix format on the host.
3. If full-frame `rgb565/rgba` payloads are too heavy, try `rgb565&compress=lz4`.
4. Prefer Raw TCP for high-frequency streaming, then stdout. Use HTTP only for debugging and browser preview.
5. Prefer WEBP for manual previews.

## Notes

- `quality=100` may use lossless WEBP on supported platforms, increasing size and encode time.
- PNG is lossless but expensive to encode, so it is not ideal for high-frame-rate preview.
- stdout is a binary stream; callers must not mix stderr logs into stdout.
- Android private APIs can change across OS versions, so performance and availability should be measured on target devices.
