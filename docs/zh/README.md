# raw_cast 文档

<p>
  <a href="./README.md">简体中文</a> ·
  <a href="../en/README.md">English</a> ·
  <a href="../ja/README.md">日本語</a>
</p>

raw_cast 是一个 Android 截图和取流工具，无需安装 APK，基于 SurfaceControl + HardwareBuffer。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [CLI.md](CLI.md) | 命令行参数、stdout 状态行、启动方式 |
| [TRANSPORTS.md](TRANSPORTS.md) | HTTP/1.1 keep-alive、Raw TCP、ADB stdout |
| [PROTOCOL.md](PROTOCOL.md) | RC01 帧格式、格式 id、LZ4 规则 |
| [INTEGRATION.md](INTEGRATION.md) | Python、Go、Node.js、Java 集成建议 |
| [PERFORMANCE.md](PERFORMANCE.md) | 格式、传输、压缩的性能取舍 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 常见启动、连接、解析问题 |

## 兼容性

目标兼容 Android 6.0 到 Android 14（SDK 23 到 34）。Android 15 及以上需要按设备实测确认。

快速开始请先阅读仓库根目录 [README.md](../../README.md)。
