# 命令行参数

<p>
  <a href="./CLI.md">简体中文</a> ·
  <a href="../en/CLI.md">English</a> ·
  <a href="../ja/CLI.md">日本語</a>
</p>

`raw_cast` 通过 `app_process` 运行，参数统一使用 `--key=value` 或 `--key value` 形式。

## 基本启动

Raw TCP 主路径：

```shell
adb forward tcp:53517 tcp:53517
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main --tcp=53517
```

stdout 模式：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --mode=stdout \
    --format=rgb565 \
    --fps=30 \
    2>/dev/null
```

> **重要：不要把 stderr 合并到 stdout。** stdout 是二进制帧通道；`2>/dev/null` 只丢弃 stderr 日志，不会影响帧流。

HTTP/1.1 仅用于调试预览和调试取流：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / com.shiyori.raw_cast.Main \
    --port=53516 --tcp=53517
```

## 端口参数

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| `--tcp=N` | `0` | Raw TCP 端口；`0` 表示关闭 |
| `--mode=stdout` | - | 不打开网络端口，直接向标准输出写帧 |
| `--port=N` | `53516` | HTTP/1.1 调试端口；`0` 表示关闭 |
| `--port-retry=N` | `10` | 端口被占用时向后重试的次数 |

网络模式建议优先开启 Raw TCP；HTTP 端口仅用于调试。stdout 模式会忽略网络端口。

## 捕获参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--format=NAME` | `rgb565` | `rgb565`、`rgba`、`png`、`webp` |
| `--width=N` | `0` | 输出宽度，`0` 表示使用设备当前尺寸 |
| `--height=N` | `0` | 输出高度，`0` 表示使用设备当前尺寸 |
| `--compress=lz4` | `none` | 只对 `rgb565/rgba` 生效 |
| `--quality=N` | `100` | WEBP 质量，范围 1..100 |
| `--fps=N` | `30` | 连续输出帧率；stdout 中 `0` 表示单帧 |
| `--oneshot` | - | stdout 下输出一帧后退出 |

不支持的格式会直接报错，不会退回默认格式。

## stdout 启动状态

网络模式启动后会向 stdout 输出结构化状态行，每行都会 flush：

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

如果某个端口完全无法绑定：

```text
BIND:HTTP=FAILED
```

如果没有任何网络传输绑定成功，会输出 `READY=0` 后退出。调用方应读取这些状态行，再按实际端口设置 `adb forward`。

`--mode=stdout` 不输出 `PID` / `BIND` / `READY` 文本，stdout 只包含二进制 banner 和 RC01 帧。需要后台 detached 且不读取 stdout 的场景，应使用固定端口并设置 `--port-retry=1`，由宿主端按约定端口转发。

## 浏览器预览

普通浏览器可以访问：

```text
http://127.0.0.1:53516/preview
```

该入口返回单帧 PNG/WEBP 图片。正式截图和取流建议优先使用 Raw TCP 或 stdout；HTTP `/screenshot` 和 `/stream` 仅作为调试入口。
