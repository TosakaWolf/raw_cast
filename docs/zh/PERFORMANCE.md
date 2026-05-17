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

## 实测参考

以下数据来自 MuMu 模拟器，Android 12，1280x720，测试 `rgb565` 解码并转换为 Mat，连续采样 300 帧。单帧 `rgb565` 未编码 payload 约 1.76 MB，转换为 Mat 后约 2.64 MB。该结果适合比较同环境下的传输和压缩策略，不代表所有真机表现。

| 组合 | 首帧 | p50 | p95 | 有效 fps | 观察 |
| --- | ---: | ---: | ---: | ---: | --- |
| stdout + `rgb565` | 611 ms | 78 ms | 108 ms | 12.37 | 端口最简单，但未压缩大帧容易被 stdout/ADB 管道拖慢 |
| stdout + `rgb565` + LZ4 | 383 ms | 16 ms | 26 ms | 58.87 | 吞吐提升明显，适合无端口自动化或兜底场景 |
| Raw TCP + `rgb565` | 102 ms | 44 ms | 63 ms | 21.89 | 未压缩时比 stdout 更稳，首帧也更低 |
| Raw TCP + `rgb565` + LZ4 | 49 ms | 17 ms | 23 ms | 57.89 | 延迟低且稳定，是该环境下实时 Mat 管线的优先组合 |

结论：`rgb565` + LZ4 在该模拟器环境中显著降低传输压力；Raw TCP 更适合作为长时间实时流主路径，stdout 更适合单通道自动化、单帧或端口不可用时的兜底。

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
