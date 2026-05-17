# RC01 协议

<p>
  <a href="./PROTOCOL.md">简体中文</a> ·
  <a href="../en/PROTOCOL.md">English</a> ·
  <a href="../ja/PROTOCOL.md">日本語</a>
</p>

本文档定义 raw_cast 的 RC01 二进制帧。Raw TCP、HTTP `/stream` 和 ADB stdout 都使用同一个帧头，客户端可以复用同一套解析代码。

HTTP `/screenshot` 和 `/preview` 直接返回 payload；尺寸、格式等元信息放在响应头。HTTP `/stream` 不写 banner，响应体是 chunked 流，每个 chunk 是完整的 RC01 帧。

## Banner

Raw TCP 和 ADB stdout 在帧序列前先写 8 字节 banner：

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       4     protocol_version = 1, little-endian uint32
```

HTTP `/stream` 没有 banner。

## RC01 帧头

每帧由 32 字节小端头部和紧随其后的 payload 组成：

```text
offset  size  field
0       4     magic = 'R','C','0','1'
4       1     version = 1
5       1     flags
6       2     format
8       4     seq
12      4     width
16      4     height
20      4     stride
24      4     payload_size
28      4     timestamp_ms
```

`flags`：

| bit | 名称 | 说明 |
| ---: | --- | --- |
| 0 | `LZ4` | payload 使用 LZ4 压缩；只对 `rgb565/rgba` 有效 |

`stride` 是 raw payload 的每行字节数。压缩图片格式使用 `0`。

## 格式 id

| id | 名称 | 类型 | 字节/像素 | payload |
| ---: | --- | --- | ---: | --- |
| 1 | `RGB565` | raw | 2 | 紧凑 RGB565 |
| 2 | `RGBA8888` | raw | 4 | RGBA 通道顺序 |
| 11 | `PNG` | image | - | PNG 字节流 |
| 12 | `WEBP` | image | - | WEBP 字节流 |

格式 id `3` 已保留不用。PNG 和 WEBP 的 id 不重新编号，保持 wire compatibility。

## LZ4

LZ4 只用于 raw payload。帧头本身不压缩，`payload_size` 表示压缩后的长度。客户端解压目标大小可以按 `width * height * bytes_per_pixel` 计算。

如果格式是 PNG 或 WEBP，服务端会忽略 `compress=lz4`。

## Raw TCP 请求行

客户端连接 Raw TCP 端口后发送一行 ASCII 参数：

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

参数用空格分隔，未知参数忽略。不支持的格式会导致连接结束并在设备日志中记录错误。

## HTTP 响应

`/screenshot` 和 `/preview` 返回单个 payload，常用响应头：

```text
X-Frame-Width: 1080
X-Frame-Height: 2400
X-Frame-Format: RAW_RGB565
X-Frame-Lz4: 0
X-Frame-Seq: 1
```

`/stream` 返回：

```text
Content-Type: application/x-raw-cast-frames
Transfer-Encoding: chunked
```

HTTP 客户端可以把解码后的响应体当作连续的 RC01 帧流读取。
