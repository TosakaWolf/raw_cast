# raw_cast ドキュメント

<p>
  <a href="../../README.md">简体中文</a> ·
  <a href="../en/README.md">English</a> ·
  <a href="./README.md">日本語</a>
</p>

raw_cast は Android のスクリーンショット取得とストリーミングのためのツールです。APK のインストールは不要で、SurfaceControl + HardwareBuffer を利用します。

## ドキュメント

| 文書 | 内容 |
| --- | --- |
| [CLI.md](CLI.md) | コマンドライン引数、stdout の状態行、起動例 |
| [TRANSPORTS.md](TRANSPORTS.md) | HTTP/1.1 keep-alive、Raw TCP、ADB stdout |
| [PROTOCOL.md](PROTOCOL.md) | RC01 フレーム形式、format id、LZ4 ルール |
| [INTEGRATION.md](INTEGRATION.md) | Python、Go、Node.js、Java 連携 |
| [PERFORMANCE.md](PERFORMANCE.md) | format、transport、compression の性能上の違い |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 起動、接続、解析のよくある問題 |

## 互換性

対象互換範囲は Android 6.0 から Android 14（SDK 23 から 34）です。Android 15 以降は端末ごとの実測確認が必要です。

クイックスタートはルートの [README.md](../../README.md) を参照してください。
