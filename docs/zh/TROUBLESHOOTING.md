# Troubleshooting

<p>
  <a href="./TROUBLESHOOTING.md">简体中文</a> ·
  <a href="../en/TROUBLESHOOTING.md">English</a> ·
  <a href="../ja/TROUBLESHOOTING.md">日本語</a>
</p>

## 进程没有启动

先不要把 stdout/stderr 重定向到空设备，直接看错误：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main --port=53516 --tcp=53517
```

常见原因：

| 现象 | 排查 |
| --- | --- |
| `ClassNotFoundException` | 确认 APK 路径正确，并且 `CLASSPATH=` 指向该 APK |
| stdout 没有 `PID` / `BIND` / `READY` | 查看 stderr 和 logcat，确认进程是否提前崩溃 |
| `BIND:HTTP=FAILED` | 端口被占用，换端口或提高 `--port-retry` |
| `no transports enabled` | HTTP 和 Raw TCP 都关闭，且没有使用 `--mode=stdout` |

## stdout 没有启动状态

网络模式必须读取 stdout。不要把 stdout 重定向到空设备：

```shell
adb shell CLASSPATH=/data/local/tmp/raw_cast.apk \
    app_process / ink.mol.raw_cast.Main \
    --port=53516 --tcp=53517
```

正常输出类似：

```text
PID=12345
BIND:HTTP=53516
BIND:TCP=53517
READY=1
```

如果仍然没有任何输出，排查步骤：

1. 前台启动，同时保留 stdout 和 stderr。
2. 执行 `adb shell logcat -d | grep raw_cast` 查看异常。
3. 确认 APK 已 push 到 `CLASSPATH` 指定路径。
4. 确认至少开启 HTTP 或 Raw TCP，或使用 `--mode=stdout`。
5. 如果必须 detached 且无法读取 stdout，只能使用固定端口并设置 `--port-retry=1`。

## 连接不上 HTTP

确认端口转发和实际绑定端口：

```shell
adb forward --list
```

如果 stdout 的 `BIND:HTTP=` 显示实际端口不是你请求的端口，请按实际端口设置 `adb forward`。

## 浏览器预览可用但取流异常

浏览器可以直接访问：

```text
http://127.0.0.1:53516/preview
```

取流请访问 `/stream`，并按 RC01 帧头解析响应体。标准 HTTP 客户端通常会自动解码 chunked 边界，应用层只需要按 `payload_size` 继续切帧。

## 请求格式报错

支持的格式只有：

```text
rgb565, rgba, png, webp
```

不支持的格式会返回 400 或在设备日志中记录错误，不会静默退回默认格式。

## 图片是黑屏或尺寸不对

可能原因：

1. 目标设备限制了 SurfaceControl 截图接口。
2. 当前屏幕处于受保护内容页面。
3. 旋转状态或外接显示导致尺寸不匹配。

可尝试浏览器预览：

```text
http://127.0.0.1:53516/preview?format=webp&quality=80
```

如果预览也异常，再查看 logcat 中的 `ScreenCaptor` 和 `raw_cast` 日志。

## 帧率不达标

排查顺序：

1. 降低分辨率：加 `width=` 和 `height=`。
2. CV 场景使用 Raw TCP + `format=rgb565`，宿主端按需要转换为 BGR/RGB 矩阵。
3. 单路极限性能使用 Raw TCP 或 stdout。
4. 带宽不足时尝试 `format=rgb565&compress=lz4`。
5. 确认主机端消费速度足够快。

## stdout 输出无法解析

stdout 是纯二进制流，应只写给文件或客户端程序。日志在 stderr。Windows shell 中请避免会改写换行的管道工具。

## 如何确认当前版本能力

搜索源码和文档中可用格式与传输：

```shell
rg -n "format=|BIND:|Raw TCP|HTTP/1.1|stdout|preview" README.md docs app/src/main/java
```
