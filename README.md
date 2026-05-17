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

### Raw TCP 快速接入

默认使用 Raw TCP + `rgb565` + 不压缩。下面是一套宿主集成时的完整流程；`adb shell` 是需要保持存活的前台进程，读到 `READY=1` 后再建立 forward 并连接 Raw TCP。

```shell
# 1. 推送运行包。raw_cast 通过 app_process 启动，不需要安装 APK。
adb push raw_cast.apk /data/local/tmp/raw_cast.apk

# 2. 启动 Raw TCP。stdout 会输出 PID/BIND/READY 状态行，stderr 只用于日志。
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=0 --tcp=53517 --port-retry=10

# 3. 等待 stdout 状态行；BIND:TCP 是设备端实际绑定端口。
# PID=12345
# BIND:TCP=53517
# READY=1

# 4. 读到 READY=1 后，在另一条宿主执行路径中建立 forward。
adb forward tcp:53517 tcp:53517

# 5. 连接 127.0.0.1:53517 后，向 Raw TCP socket 发送一行请求。
# format=rgb565 fps=120 width=0 height=0 compress=none
```

### 端口、压缩与压测

- 如果设备端端口被占用且 `--port-retry` 绑定到了后续端口，请按 stdout 中的实际端口 forward。例如 `BIND:TCP=53519` 时使用 `adb forward tcp:53517 tcp:53519`。
- 如果 `rgb565/rgba` 未编码全帧像素负载对 ADB 链路压力较大，保持同一条 Raw TCP 连接，把请求行改为 `format=rgb565 fps=120 width=0 height=0 compress=lz4`。
- 如果只是人工调试固定端口，可先执行 `adb forward tcp:53517 tcp:53517`，再用 `--port-retry=1` 启动，避免设备端自动换端口。
- Benchmark 建议每个“传输 + 像素格式 + 压缩方式”组合只初始化一次流，先预热再统计连续取帧；每个组合测完后停止 reader、forward 和远端进程。

### HTTP 与 stdout 通道

HTTP 调试预览：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517 --port-retry=10

# 读到 READY=1 后，按 BIND 输出的实际端口 forward。
adb forward tcp:53516 tcp:53516
adb forward tcp:53517 tcp:53517

# HTTP stream 仍默认建议 rgb565，LZ4 可选。
# http://127.0.0.1:53516/stream?format=rgb565&fps=120&compress=none
# http://127.0.0.1:53516/stream?format=rgb565&fps=120&compress=lz4
```

ADB stdout 二进制流。stdout 模式不打开网络端口，stdout 是纯 RC01 二进制流，不输出 `PID/BIND/READY` 文本；不要把 stderr 合并到 stdout。

```shell
adb exec-out sh -c 'CLASSPATH=/data/local/tmp/raw_cast.apk app_process / ink.mol.raw_cast.Main --mode=stdout --format=rgb565 --fps=120 --compress=none 2>/dev/null'
adb exec-out sh -c 'CLASSPATH=/data/local/tmp/raw_cast.apk app_process / ink.mol.raw_cast.Main --mode=stdout --format=rgb565 --fps=120 --compress=lz4 2>/dev/null'
adb exec-out sh -c 'CLASSPATH=/data/local/tmp/raw_cast.apk app_process / ink.mol.raw_cast.Main --mode=stdout --format=rgb565 --oneshot --compress=none 2>/dev/null'
```

## 可选参数

### 启动参数

启动参数用于 `app_process`。网络模式推荐只配置端口；像素格式、FPS、压缩等通常由 Raw TCP 请求行或 HTTP query 决定。stdout 模式直接使用启动参数里的截图选项。

| 参数 | 常用 | 默认 | 可选值 | 说明 |
| --- | --- | --- | --- | --- |
| `--tcp=N` | 是 | `0` | `0..65535` | Raw TCP 端口；`0` 表示关闭 |
| `--port=N` | 调试常用 | `53516` | `0..65535` | HTTP/1.1 端口；`0` 表示关闭 |
| `--port-retry=N` | 是 | `10` | `1..100` | 端口占用时向后尝试的次数 |
| `--mode=stdout` / `--stdout` | 是 | 关闭 | - | stdout 二进制流模式，不打开网络端口 |
| `--format=NAME` | stdout 常用 | `rgb565` | `rgb565`、`rgba`、`png`、`webp` | stdout 输出格式；网络模式通常改用请求参数 |
| `--fps=N` | stdout 常用 | `30` | `0..120` | stdout 帧率；stdout 中 `0` 表示单帧 |
| `--compress=lz4` / `--lz4` | 常用 | `none` | `none`、`lz4` | `rgb565/rgba` 可选 LZ4；PNG/WEBP 不叠加 LZ4 |
| `--oneshot` | stdout 常用 | 关闭 | - | stdout 输出一帧后退出 |
| `--width=N` | 按需 | `0` | `0..` | 输出宽度；`0` 使用当前设备尺寸 |
| `--height=N` | 按需 | `0` | `0..` | 输出高度；`0` 使用当前设备尺寸 |
| `--quality=N` | 图片格式按需 | `100` | `1..100` | WEBP 质量；仅图片格式使用 |

### 取流 / 截图请求参数

Raw TCP 在连接后发送一行空格分隔的 `key=value`；HTTP 使用 query string。不同模式默认推荐 `format=rgb565`，可选 `compress=lz4`。

| 参数 | 常用 | 默认 | 可选值 | 适用 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `format` | 是 | `rgb565` | `rgb565`、`rgba`、`png`、`webp` | Raw TCP / HTTP | 输出像素或图片格式 |
| `fps` | 是 | `30` | Raw TCP: `0..120`；HTTP stream: `1..120` | Raw TCP / HTTP stream | 连续取流帧率；Raw TCP 中 `0` 表示单帧 |
| `compress` | 是 | `none` | `none`、`lz4` | `rgb565/rgba` | LZ4 压缩开关 |
| `width` | 按需 | `0` | `0..` | Raw TCP / HTTP | 输出宽度；`0` 使用当前设备尺寸 |
| `height` | 按需 | `0` | `0..` | Raw TCP / HTTP | 输出高度；`0` 使用当前设备尺寸 |
| `quality` | 图片格式按需 | `100` | `1..100` | `webp` | WEBP 质量 |

## 推荐实践

| 场景 | 推荐方式 |
| --- | --- |
| 实时 Mat / OpenCV / 推理 | Raw TCP，`format=rgb565`，优先测试 `compress=lz4` |
| 无端口或自动化兜底 | ADB stdout，`format=rgb565`，可选 `compress=lz4` |
| 对比协议和传输开销 | 分别测试 stdout / Raw TCP × `rgb565` / `rgba` × `none` / `lz4` |
| 需要完整 4 通道 `rgba` 像素 | `format=rgba`，同样可选 `compress=lz4` |
| 浏览器人工查看 | HTTP `/preview`，仅作为调试预览，不作为高频 benchmark 通道 |

Benchmark 建议每个“传输 + 像素格式 + 压缩方式”组合只初始化一次流，先预热再统计连续取帧；首帧耗时单独记录。每个组合测完后停止该类型的 reader、forward 和远端进程，避免影响下一个组合。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 运行方式 | **无需安装 APK**，`adb push` 后通过 `app_process` 启动 |
| 截图路径 | **SurfaceControl + HardwareBuffer** |
| 正式传输 | **HTTP/1.1 keep-alive**、**Raw TCP**、**ADB stdout** |
| 默认像素 | 各模式推荐默认 `RGB_565`，需要完整 4 通道时使用 `RGBA_8888` |
| 压缩 | `rgb565/rgba` 可选 LZ4；PNG/WEBP 不叠加 LZ4 |
| 浏览器查看 | HTTP `/preview` 返回单帧预览图片 |

## 传输与格式

| 传输 | 入口 | 内容 |
| --- | --- | --- |
| HTTP/1.1 keep-alive | `--port=PORT` | `/screenshot`、`/preview`、`/stream` |
| Raw TCP | `--tcp=PORT` | 8 字节 banner 后连续输出 RC01 帧 |
| ADB stdout | `--mode=stdout` | 8 字节 banner 后从标准输出连续输出 RC01 帧 |

通用参数：

```text
width=NNN  height=NNN  format=rgb565|rgba|png|webp
compress=none|lz4  quality=1..100  fps=1..120
```

`compress=lz4` 只对 `rgb565/rgba` 生效。`quality=` 只对 WEBP 有意义。

| `format=` | 协议 id | 字节/像素 | 用途 |
| --- | ---: | ---: | --- |
| `rgb565` | 1 | 2 | 默认推荐；相比 `rgba` 像素负载减半，但仍是未编码全帧数据 |
| `rgba` | 2 | 4 | Android 原生 4 通道顺序 |
| `png` | 11 | - | 无损图片 |
| `webp` | 12 | - | 有损或无损图片，取决于 `quality=` 和系统版本 |

格式 id `3` 已保留不用；PNG 保持 id `11`，WEBP 保持 id `12`，以兼容已有客户端。

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

## 性能基准

MuMu 模拟器（Android 12，1280x720）下的 `rgb565` 转 Mat 测试显示：`rgb565` 像素负载相比 `rgba` 更小，转 Mat 后约 2.64 MB；开启 LZ4 后 stdout 和 Raw TCP 都能接近 58 fps；未压缩时 Raw TCP 明显优于 stdout，首帧和持续延迟更低。

更完整的测试环境、指标和建议见 [docs/zh/PERFORMANCE.md](docs/zh/PERFORMANCE.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/zh/README.md](docs/zh/README.md) | 中文文档入口与快速开始 |
| [docs/en/README.md](docs/en/README.md) | English docs and quick start |
| [docs/ja/README.md](docs/ja/README.md) | 日本語ドキュメントとクイックスタート |
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
