# 传输方式

<p>
  <a href="./TRANSPORTS.md">简体中文</a> ·
  <a href="../en/TRANSPORTS.md">English</a> ·
  <a href="../ja/TRANSPORTS.md">日本語</a>
</p>

raw_cast 的主要传输方式为 Raw TCP 和 ADB stdout。HTTP/1.1 keep-alive 仅用于浏览器预览、人工排查和调试取流。

## Raw TCP

Raw TCP 适合本地程序、OpenCV 或需要最低协议开销的客户端。

```shell
adb forward tcp:53517 tcp:53517
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main --tcp=53517
```

客户端连接后发送一行 ASCII 请求：

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

服务端先返回 8 字节 banner，然后连续返回 RC01 帧。`fps=0` 表示发送一帧后关闭连接。

## ADB stdout

stdout 模式不需要端口转发，适合自动化和本机程序。

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --mode=stdout \
    --format=rgb565 \
    --fps=30 \
    2>/dev/null
```

> **重要：不要把 stderr 合并到 stdout。** stdout 是 8 字节 banner + 连续 RC01 帧的二进制通道；stderr 只能作为日志通道丢弃或单独读取。

## HTTP/1.1 Keep-Alive

HTTP 仅用于调试，端口默认 53516：

```shell
adb forward tcp:53516 tcp:53516
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main --port=53516
```

端点：

| 路径 | 内容 |
| --- | --- |
| `/screenshot` | 单帧 `rgb565/rgba`、PNG 或 WEBP；尺寸、格式等信息在 `X-Frame-*` 响应头 |
| `/preview` | 单帧图片，默认 PNG，适合浏览器人工查看 |
| `/stream` | `application/x-raw-cast-frames` chunked 调试流；每个 chunk 是一帧 RC01 数据 |

HTTP 通道便于浏览器和标准客户端排查，不作为高频 benchmark 的默认通道。

## 参数

Raw TCP、ADB stdout 和 HTTP 调试通道共享这些参数：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `format` | `rgb565` `rgba` `png` `webp` | 输出格式 |
| `width` / `height` | 整数 | `0` 表示设备当前尺寸 |
| `compress` | `lz4` 或 `none` | 只对 `rgb565/rgba` 生效 |
| `quality` | `1..100` | WEBP 质量 |
| `fps` | `0..120` 或 `1..120` | 连续流帧率；Raw TCP 支持 `0` 单帧 |

## 选择建议

| 场景 | 推荐 |
| --- | --- |
| OpenCV 实时处理 | Raw TCP `format=rgb565` |
| 自动化管线 | ADB stdout |
| 浏览器人工查看 | HTTP `/preview` |
| 标准 HTTP 客户端调试 | HTTP `/screenshot` 或 `/stream` |
