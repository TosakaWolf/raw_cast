<div align="center">

<h1>raw_cast</h1>

<p>
  <a href="./README.md">简体中文</a> ·
  <a href="./docs/en/README.md">English</a> ·
  <a href="./docs/ja/README.md">日本語</a>
</p>

<p>
  <a href="./LICENSE"><img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0--or--later-blue"></a>
  <a href="https://github.com/TosakaWolf/raw_cast/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/TosakaWolf/raw_cast?style=flat"></a>
  <a href="https://github.com/TosakaWolf/raw_cast/releases"><img alt="GitHub downloads" src="https://img.shields.io/github/downloads/TosakaWolf/raw_cast/total?label=downloads"></a>
</p>

<p>
  <strong>raw_cast 是一个 Android 截图和取流工具，无需安装 APK，基于 SurfaceControl + HardwareBuffer。</strong>
</p>

</div>

## 快速开始

### 手动启动

先把 APK 推到设备，再建立固定端口转发。手动模式建议使用 `--port-retry=1`，避免设备端自动换端口后与本机 `adb forward` 不一致。

```shell
adb push raw_cast-release-1.0.apk /data/local/tmp/raw_cast.apk

adb forward tcp:53516 tcp:53516
adb forward tcp:53517 tcp:53517

adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517 --port-retry=1
```

网络模式启动成功后，设备进程会在 stdout 输出：

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

调用方读到 `READY=1` 后即可连接已转发端口。浏览器打开 `http://127.0.0.1:53516/preview` 可以查看单帧预览。

### ADB stdout

stdout 模式不打开网络端口，stdout 只输出 RC01 二进制流。不要把 stderr 合并到 stdout。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --mode=stdout --format=bgra --fps=30 > stream.bin
```

抓取一帧：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --mode=stdout --format=bgra --oneshot > frame.bin
```

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 运行方式 | **无需安装 APK**，`adb push` 后通过 `app_process` 启动 |
| 截图路径 | **SurfaceControl + HardwareBuffer** |
| 正式传输 | **HTTP/1.1 keep-alive**、**Raw TCP**、**ADB stdout** |
| 浏览器查看 | HTTP `/preview` 返回单帧 PNG/WEBP 预览 |
| 像素格式 | `RGB_565` / `RGBA_8888` / `BGRA_8888` / PNG / WEBP |
| 压缩 | raw 格式可选 LZ4；WEBP 可配置 `quality=` |

## 传输与格式

| 传输 | 入口 | 内容 |
| --- | --- | --- |
| HTTP/1.1 keep-alive | `--port=PORT` | `/screenshot`、`/preview`、`/stream` |
| Raw TCP | `--tcp=PORT` | 8 字节 banner 后连续输出 RC01 帧 |
| ADB stdout | `--mode=stdout` | 8 字节 banner 后从标准输出连续输出 RC01 帧 |

通用参数：

```text
width=NNN  height=NNN  format=rgb565|rgba|bgra|png|webp
compress=lz4  quality=1..100  fps=1..120
```

`compress=lz4` 只对 raw 格式生效。`quality=` 只对 WEBP 有意义。

| `format=` | 协议 id | 字节/像素 | 用途 |
| --- | ---: | ---: | --- |
| `rgb565` | 1 | 2 | 默认 raw 格式，带宽最低 |
| `rgba` | 2 | 4 | Android 原生 4 通道顺序 |
| `bgra` | 3 | 4 | OpenCV 友好，可直接 reshape 为 4 通道矩阵 |
| `png` | 11 | - | 无损图片 |
| `webp` | 12 | - | 有损或无损图片，取决于 `quality=` 和系统版本 |

PNG 保持 id `11`，WEBP 保持 id `12`，以兼容已有客户端。

## RC01 帧

Raw TCP、HTTP `/stream` 和 ADB stdout 使用同一个 32 字节小端帧头：

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       1     version = 1
5       1     flags, bit0 = LZ4
6       2     format, see PixelFmt id
8       4     seq
12      4     width
16      4     height
20      4     stride
24      4     payload_size
28      4     timestamp_ms
```

Raw TCP 和 ADB stdout 会先输出 8 字节 banner：

```text
'R','C','0','1' + little-endian uint32 protocol_version
```

HTTP `/stream` 使用 chunked response；每个 chunk 是一帧完整的 RC01 header + payload。详见 [docs/zh/PROTOCOL.md](docs/zh/PROTOCOL.md)。

## 兼容性

目标兼容 Android 6.0 到 Android 14（SDK 23 到 34）。Android 15 及以上需要按设备实测确认。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/zh/CLI.md](docs/zh/CLI.md) | 命令行参数、端口输出和启动示例 |
| [docs/zh/TRANSPORTS.md](docs/zh/TRANSPORTS.md) | HTTP/1.1 keep-alive、Raw TCP、stdout |
| [docs/zh/PROTOCOL.md](docs/zh/PROTOCOL.md) | RC01 帧头、格式 id、LZ4 规则 |
| [docs/zh/INTEGRATION.md](docs/zh/INTEGRATION.md) | 宿主端集成建议 |
| [docs/zh/PERFORMANCE.md](docs/zh/PERFORMANCE.md) | 格式、传输和性能建议 |
| [docs/zh/TROUBLESHOOTING.md](docs/zh/TROUBLESHOOTING.md) | 常见问题排查 |

## 致谢与参考

- [DroidCast_raw](https://github.com/Torther/DroidCast_raw)

## License

raw_cast is licensed under the GNU Affero General Public License v3.0 or later.

SPDX-License-Identifier: `AGPL-3.0-or-later`

Third-party code and referenced upstream work retain their original licenses.
See [NOTICE](NOTICE) and the license files inside each third-party directory,
including `app/src/main/cpp/lz4`.
