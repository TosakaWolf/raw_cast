# 宿主接入

<p>
  <a href="./INTEGRATION.md">简体中文</a> ·
  <a href="../en/INTEGRATION.md">English</a> ·
  <a href="../ja/INTEGRATION.md">日本語</a>
</p>

本页给出宿主端接入方式。示例假设 APK 已经位于 `/data/local/tmp/raw_cast.apk`。

## 启动器

网络模式需要保持 `adb shell` 进程，并从 stdout 读取启动状态。stderr 只作为日志通道，不参与端口协议：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --port=53516 --tcp=53517
```

读到 `READY=1` 后再按实际端口设置 forward：

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

`--mode=stdout` 是另一种模式：stdout 会直接输出二进制 RC01 流，不能按文本状态读取。

## Raw TCP

Raw TCP 客户端连接端口后，先发送一行 ASCII 参数：

```text
format=rgb565 fps=30 width=0 height=0 compress=none
```

随后读取 8 字节 banner，再按 [PROTOCOL.md](PROTOCOL.md) 解析连续 RC01 帧。

## stdout

stdout 模式适合调用方直接消费二进制流：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --mode=stdout \
    --format=rgb565 \
    --fps=30 \
    2>/dev/null
```

该模式不输出 `PID` / `BIND` / `READY` 文本。
宿主端应从子进程 stdout pipe 读取 banner 和 RC01 帧。

> **重要：不要把 stderr 合并到 stdout。** `2>/dev/null` 只丢弃 stderr 日志；如果需要日志，请单独读取 stderr。

## HTTP 调试

HTTP 仅用于浏览器预览和标准客户端调试。可直接访问 `/screenshot`、`/preview` 和 `/stream`。

单帧接口：

```text
GET http://127.0.0.1:53516/screenshot?format=png
GET http://127.0.0.1:53516/preview?format=webp&quality=80
```

流式接口：

```text
GET http://127.0.0.1:53516/stream?format=rgb565&fps=30
```

读取 `/stream` 时，HTTP 客户端通常会处理 chunked 编码；应用层按 RC01 帧头中的 `payload_size` 继续切帧即可。
