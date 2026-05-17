# raw_cast Docs

<p>
  <a href="../../README.md">简体中文</a> ·
  <a href="./README.md">English</a> ·
  <a href="../ja/README.md">日本語</a>
</p>

raw_cast is an Android screenshot and streaming tool. It does not require APK installation and is based on SurfaceControl + HardwareBuffer.

## Documents

| Document | Contents |
| --- | --- |
| [CLI.md](CLI.md) | Command-line options, stdout status lines, launch examples |
| [TRANSPORTS.md](TRANSPORTS.md) | HTTP/1.1 keep-alive, Raw TCP, ADB stdout |
| [PROTOCOL.md](PROTOCOL.md) | RC01 frame format, format ids, LZ4 rules |
| [INTEGRATION.md](INTEGRATION.md) | Python, Go, Node.js, and Java integration notes |
| [PERFORMANCE.md](PERFORMANCE.md) | Format, transport, and compression tradeoffs |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common launch, connection, and parsing issues |

## Compatibility

Target compatibility: Android 6.0 through Android 14, SDK 23 through 34. Android 15 and later should be verified per device.

Start with the root [README.md](../../README.md) for the quick start.
