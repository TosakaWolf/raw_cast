# 性能参考

<p>
  <a href="./PERFORMANCE.md">简体中文</a> ·
  <a href="../en/PERFORMANCE.md">English</a> ·
  <a href="../ja/PERFORMANCE.md">日本語</a>
</p>

raw_cast 的耗时主要来自四部分：截图、像素转换、编码或压缩、传输。实际数值会随设备、分辨率、系统版本和 USB 链路变化。

## 输出格式选择

| 格式 | 单帧负载 | CPU 成本 | 适合场景 |
| --- | --- | --- | --- |
| `rgb565` | 相比 `rgba` 减半，仍是未编码全帧像素 | 低 | 实时截图、CV、推理主路径 |
| `rgba` | `rgb565` 的 2 倍，仍是未编码全帧像素 | 低 | 需要完整 4 通道像素 |
| `png` | 取决于画面内容，通常小于未编码像素 | 高 | 无损单帧、对比测试 |
| `webp` | 取决于质量和画面内容 | 中 | 浏览器预览、压缩单帧 |
| `rgb565` + LZ4 | 压缩后大小取决于画面变化 | 中 | ADB 链路压力较大时的实时 `rgb565/rgba` 流 |

`compress=lz4` 只对 `rgb565/rgba` 有意义。PNG 和 WEBP 已经是压缩图片，不再叠加 LZ4。

## 传输选择

| 传输 | 协议开销 | 优点 | 建议 |
| --- | --- | --- | --- |
| Raw TCP | 低 | 简单、稳定、易解析 | OpenCV、推理管线 |
| ADB stdout | 最低 | 不经过端口转发 | 自动化、本机程序 |
| HTTP/1.1 keep-alive | 中 | 标准客户端、浏览器预览、易集成 | 浏览器预览和调试取流 |

HTTP `/stream` 使用长连接和 chunked response，避免每帧重新建连。它比 Raw TCP 多 HTTP chunk 边界和响应头开销，适合调试和标准客户端排查，不作为高频 benchmark 的默认通道。

## 端口、压缩与压测

- 如果设备端端口被占用且 `--port-retry` 绑定到了后续端口，请按 stdout 中的实际端口 forward。例如 `BIND:TCP=53519` 时使用 `adb forward tcp:53517 tcp:53519`。
- 如果只是人工调试固定端口，可先执行 `adb forward tcp:53517 tcp:53517`，再用 `--port-retry=1` 启动，避免设备端自动换端口。
- 如果 `rgb565/rgba` 未编码全帧像素负载对 ADB 链路压力较大，保持同一条 Raw TCP 连接，把请求行改为 `format=rgb565 fps=120 width=0 height=0 compress=lz4`。
- Benchmark 建议每个“传输 + 像素格式 + 压缩方式”组合只初始化一次流，先预热再统计连续取帧；首帧耗时单独记录。
- 每个组合测完后停止该类型的 reader、forward 和远端进程，避免影响下一个组合。

## 推荐组合

| 场景 | 推荐参数 |
| --- | --- |
| CV 或推理实时处理 | Raw TCP，`format=rgb565`，宿主端自行转换颜色 |
| ADB 链路压力较大的 `rgb565/rgba` 流 | Raw TCP，`format=rgb565&compress=lz4` |
| 标准客户端调试取流 | HTTP，`/stream?format=rgb565&fps=30` |
| 调试无损截图 | HTTP，`/screenshot?format=png` |
| 浏览器人工预览 | `/preview?format=webp&quality=80` |
| 自动化单帧 | stdout，`--format=rgb565 --oneshot` |

## 调优顺序

1. 先确认截图尺寸是否必要，降低分辨率通常最有效。
2. CV 场景优先用 `rgb565` 降低传输和设备端处理压力，在宿主端转换为需要的矩阵格式。
3. `rgb565/rgba` 未编码全帧像素负载过大时尝试 `rgb565&compress=lz4`。
4. 高频取流优先 Raw TCP，其次 stdout；HTTP 仅用于调试和浏览器预览。
5. 人工预览优先用 WEBP。

## 注意事项

- `quality=100` 在支持的平台上可能走 WEBP 无损，体积和耗时都会上升。
- PNG 无损但编码成本高，不适合高帧率预览。
- stdout 输出是二进制流，调用方不要把 stderr 日志混入 stdout。
- Android 私有接口可能在系统版本升级后变化，性能和可用性都需要实测确认。
