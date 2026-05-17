# Performance

<p>
  <a href="./PERFORMANCE.md">简体中文</a> ·
  <a href="../en/PERFORMANCE.md">English</a> ·
  <a href="../ja/PERFORMANCE.md">日本語</a>
</p>

raw_cast 的耗时主要来自四部分：截图、像素转换、编码或压缩、传输。实际数值会随设备、分辨率、系统版本和 USB 链路变化。

## 格式选择

| 格式 | 带宽 | CPU 成本 | 适合场景 |
| --- | --- | --- | --- |
| `rgb565` | 低 | 低 | 低带宽 raw 数据 |
| `png` | 低到中 | 高 | 无损单帧、对比测试 |
| `webp` | 低 | 中 | 浏览器预览、低带宽单帧 |
| raw + LZ4 | 中 | 中 | 弱链路下的实时 raw 流 |

`compress=lz4` 只对 raw 格式有意义。PNG 和 WEBP 已经是压缩图片，不再叠加 LZ4。

## 传输选择

| 传输 | 协议开销 | 优点 | 建议 |
| --- | --- | --- | --- |
| ADB stdout | 最低 | 不经过端口转发 | 自动化、本机程序 |
| Raw TCP | 低 | 简单、稳定、易解析 | OpenCV、推理管线 |
| HTTP/1.1 keep-alive | 中 | 标准客户端、浏览器预览、易集成 | 普通应用集成、单帧截图、长连接取流 |

HTTP `/stream` 使用长连接和 chunked response，避免每帧重新建连。它比 Raw TCP 多 HTTP chunk 边界和响应头开销，但换来更好的通用客户端兼容性。

## 推荐组合

| 场景 | 推荐参数 |
| --- | --- |
| CV 或推理实时处理 | Raw TCP，`format=rgb565`，宿主端自行转换颜色 |
| 弱链路 raw 流 | Raw TCP，`format=rgb565&compress=lz4` |
| 标准客户端取流 | HTTP，`/stream?format=rgb565&fps=30` |
| 无损截图 | HTTP，`/screenshot?format=png` |
| 浏览器人工预览 | `/preview?format=webp&quality=80` |
| 自动化单帧 | stdout，`--format=rgb565 --oneshot` |

## 调优顺序

1. 先确认截图尺寸是否必要，降低分辨率通常最有效。
2. CV 场景优先用 `rgb565` 降低传输和设备端处理压力，在宿主端转换为需要的矩阵格式。
3. 带宽不足时尝试 `rgb565` 或 raw + LZ4。
4. 标准客户端优先 HTTP；极限单路吞吐优先 Raw TCP 或 stdout。
5. 人工预览优先用 WEBP。

## 注意事项

- `quality=100` 在支持的平台上可能走 WEBP 无损，体积和耗时都会上升。
- PNG 无损但编码成本高，不适合高帧率预览。
- stdout 输出是二进制流，调用方不要把日志混入 stdout。
- Android 私有接口可能在系统版本升级后变化，性能和可用性都需要实测确认。
