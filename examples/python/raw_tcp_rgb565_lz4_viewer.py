#!/usr/bin/env python3
"""
Raw TCP raw-pixel viewer for raw_cast.

The script auto-detects raw_cast*.apk in the current working directory.
If no APK is present, it downloads the latest release APK from
TosakaWolf/raw_cast:

    cd examples/python
    python raw_tcp_rgb565_lz4_viewer.py --adb-address 127.0.0.1:16384

The script pushes the APK, starts raw_cast through app_process, opens both
Raw TCP and HTTP/1.1 debug ports, then displays the Raw TCP raw-pixel stream
with Tkinter. HTTP is only started and forwarded for manual debugging; open
the printed /preview or /screenshot URLs in a browser or another HTTP client.
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import json
import os
import queue
import shutil
import signal
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Deque, Iterable, Optional


PLATFORM_TOOLS_URLS = {
    "linux": "https://i0.shiyori.com/static/files/platform-tools-latest-linux.zip",
    "darwin": "https://i0.shiyori.com/static/files/platform-tools-latest-darwin.zip",
    "windows": "https://i0.shiyori.com/static/files/platform-tools-latest-windows.zip",
}
RAW_CAST_RELEASE_REPO = "TosakaWolf/raw_cast"
GITHUB_API = "https://api.github.com"
HTTP_USER_AGENT = "raw_cast-python-example"
DOWNLOAD_CHUNK_SIZE = 1024 * 1024
DOWNLOAD_PROGRESS_INTERVAL = 0.2
MAGIC = b"RC01"
PROTOCOL_VERSION = 1
FRAME_HEADER_SIZE = 32
FLAG_LZ4 = 0x01
FORMAT_RGB565 = 1
FORMAT_RGBA = 2
RGB565_BPP = 2
RGBA_BPP = 4
FORMAT_IDS = {
    "rgb565": FORMAT_RGB565,
    "rgba": FORMAT_RGBA,
}
FORMAT_BYTES_PER_PIXEL = {
    "rgb565": RGB565_BPP,
    "rgba": RGBA_BPP,
}
WARMUP_FRAMES = 2
DEFAULT_FORMAT = "rgb565"
DEFAULT_COMPRESS = "lz4"
HEADER_STRUCT = struct.Struct("<IBBHIIIIII")
_PRINT_LOCK = threading.Lock()
_INLINE_ACTIVE = False
_INLINE_WIDTH = 0
_ANSI_READY = False


def enable_ansi_control() -> bool:
    global _ANSI_READY
    if _ANSI_READY:
        return True
    if not sys.stdout.isatty():
        return False
    if os.name != "nt":
        _ANSI_READY = True
        return True
    try:
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.GetStdHandle(-11)
        mode = ctypes.c_uint32()
        if handle == ctypes.c_void_p(-1).value or not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        if kernel32.SetConsoleMode(handle, mode.value | 0x0004):
            _ANSI_READY = True
            return True
    except Exception:
        return False
    return False


def log(message: str, *, file=sys.stdout) -> None:
    global _INLINE_ACTIVE, _INLINE_WIDTH
    with _PRINT_LOCK:
        if _INLINE_ACTIVE:
            print(file=sys.stdout, flush=True)
            _INLINE_ACTIVE = False
            _INLINE_WIDTH = 0
        print(message, file=file, flush=True)


def print_inline_status(message: str, *, done: bool = False, min_width: int = 96) -> None:
    global _INLINE_ACTIVE, _INLINE_WIDTH
    with _PRINT_LOCK:
        width = max(min_width, _INLINE_WIDTH, len(message))
        if enable_ansi_control():
            sys.stdout.write(f"\r\x1b[2K{message}")
        else:
            sys.stdout.write(f"\r{message:<{width}}")
        if done:
            sys.stdout.write("\n")
        sys.stdout.flush()
        _INLINE_ACTIVE = not done
        _INLINE_WIDTH = 0 if done else width


def finish_inline_status() -> None:
    global _INLINE_ACTIVE, _INLINE_WIDTH
    with _PRINT_LOCK:
        if _INLINE_ACTIVE:
            sys.stdout.write("\n")
            sys.stdout.flush()
            _INLINE_ACTIVE = False
            _INLINE_WIDTH = 0


@dataclasses.dataclass(frozen=True)
class FrameHeader:
    version: int
    flags: int
    fmt: int
    seq: int
    width: int
    height: int
    stride: int
    payload_size: int
    timestamp_ms: int


@dataclasses.dataclass
class FrameItem:
    image: object
    seq: int


class BenchState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._overlay = "bench\nwaiting for stream"
        self._summary: Optional[str] = None

    def set_overlay(self, text: str) -> None:
        with self._lock:
            self._overlay = text

    def set_summary(self, summary: str, overlay: str) -> None:
        with self._lock:
            self._summary = summary
            self._overlay = overlay

    def set_summary_only(self, summary: str) -> None:
        with self._lock:
            self._summary = summary

    def overlay(self) -> str:
        with self._lock:
            return self._overlay

    def summary(self) -> Optional[str]:
        with self._lock:
            return self._summary


class StreamPump:
    def __init__(self, stream, name: str, echo: bool = False) -> None:
        self.name = name
        self.echo = echo
        self.lines: "queue.Queue[str]" = queue.Queue()
        self.tail: Deque[str] = collections.deque(maxlen=80)
        self.thread = threading.Thread(target=self._run, args=(stream,), name=f"{name}-pump", daemon=True)
        self.thread.start()

    def _run(self, stream) -> None:
        try:
            for line in stream:
                clean = line.rstrip("\r\n")
                self.tail.append(clean)
                self.lines.put(clean)
                if self.echo:
                    log(f"[{self.name}] {clean}", file=sys.stderr)
        except Exception as exc:
            self.tail.append(f"<pump error: {exc}>")


class Metrics:
    def __init__(self, fps_window: float) -> None:
        self.fps_window = fps_window
        self.reset(time.perf_counter())

    def reset(self, now: float) -> None:
        self.start = now
        self.frames = 0
        self.first_frame_ms: Optional[float] = None
        self.last_frame_time: Optional[float] = None
        self.intervals_ms: Deque[float] = collections.deque(maxlen=2000)
        self.frame_times: Deque[float] = collections.deque(maxlen=1000)
        self.byte_events: Deque[tuple[float, int]] = collections.deque(maxlen=1000)
        self.total_payload_bytes = 0
        self.last_seq = 0
        self.last_width = 0
        self.last_height = 0
        self.last_payload_size = 0
        self.last_decoded_size = 0

    def observe(self, header: FrameHeader, payload_size: int, decoded_size: int, now: float) -> None:
        if self.frames == 0:
            self.first_frame_ms = (now - self.start) * 1000.0
        elif self.last_frame_time is not None:
            self.intervals_ms.append((now - self.last_frame_time) * 1000.0)
        self.frames += 1
        self.last_frame_time = now
        self.frame_times.append(now)
        self.byte_events.append((now, payload_size))
        self.total_payload_bytes += payload_size
        self.last_seq = header.seq
        self.last_width = header.width
        self.last_height = header.height
        self.last_payload_size = payload_size
        self.last_decoded_size = decoded_size
        self._trim_recent(now)

    def _trim_recent(self, now: float) -> None:
        cutoff = now - self.fps_window
        while self.frame_times and self.frame_times[0] < cutoff:
            self.frame_times.popleft()
        while self.byte_events and self.byte_events[0][0] < cutoff:
            self.byte_events.popleft()

    def summary(self) -> str:
        now = time.perf_counter()
        self._trim_recent(now)
        elapsed = max(now - self.start, 1e-6)
        avg_fps = self.frames / elapsed
        recent_fps = self._recent_fps(now)
        avg_mib = (self.total_payload_bytes / 1048576.0) / elapsed
        recent_mib = self._recent_mib(now)
        first = self.first_frame_ms or 0.0
        p50 = percentile(self.intervals_ms, 50)
        p95 = percentile(self.intervals_ms, 95)
        return (
            f"frames={self.frames} seq={self.last_seq} "
            f"fps={recent_fps:.2f}/{avg_fps:.2f} "
            f"first={first:.1f}ms p50={p50:.1f}ms p95={p95:.1f}ms "
            f"throughput={recent_mib:.2f}/{avg_mib:.2f}MiB/s "
            f"size={self.last_width}x{self.last_height} "
            f"payload={self.last_payload_size / 1024.0:.1f}KiB "
            f"decoded={self.last_decoded_size / 1048576.0:.2f}MiB"
        )

    def overlay_text(self, bench_label: str) -> str:
        now = time.perf_counter()
        self._trim_recent(now)
        elapsed = max(now - self.start, 1e-6)
        avg_fps = self.frames / elapsed
        recent_fps = self._recent_fps(now)
        avg_mib = (self.total_payload_bytes / 1048576.0) / elapsed
        recent_mib = self._recent_mib(now)
        first = self.first_frame_ms or 0.0
        p50 = percentile(self.intervals_ms, 50)
        p95 = percentile(self.intervals_ms, 95)
        return "\n".join(
            [
                bench_label,
                f"frames {self.frames}  seq {self.last_seq}",
                f"fps {recent_fps:.2f} / avg {avg_fps:.2f}",
                f"first {first:.1f} ms",
                f"p50 {p50:.1f} ms  p95 {p95:.1f} ms",
                f"net {recent_mib:.2f} / avg {avg_mib:.2f} MiB/s",
                f"size {self.last_width}x{self.last_height}",
                f"payload {self.last_payload_size / 1024.0:.1f} KiB",
                f"decoded {self.last_decoded_size / 1048576.0:.2f} MiB",
            ]
        )

    def _recent_fps(self, now: float) -> float:
        if len(self.frame_times) < 2:
            return 0.0
        span = max(self.frame_times[-1] - self.frame_times[0], 1e-6)
        return (len(self.frame_times) - 1) / span

    def _recent_mib(self, now: float) -> float:
        if not self.byte_events:
            return 0.0
        span = min(self.fps_window, max(now - self.byte_events[0][0], 1e-6))
        return (sum(size for _, size in self.byte_events) / 1048576.0) / span


def percentile(values: Iterable[float], pct: int) -> float:
    data = sorted(values)
    if not data:
        return 0.0
    idx = int(round((len(data) - 1) * pct / 100.0))
    return data[max(0, min(idx, len(data) - 1))]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Start raw_cast and view Raw TCP raw-pixel frames with Tkinter.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--remote-apk", default="/data/local/tmp/raw_cast.apk", help="device APK path")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument(
        "--adb-address",
        help="run 'adb connect HOST:PORT' before starting; also used as --serial when --serial is omitted",
    )
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--tcp", type=int, default=53517, help="local/requested Raw TCP port")
    parser.add_argument("--http", type=int, default=53516, help="local/requested HTTP debug port; 0 disables HTTP")
    parser.add_argument("--format", choices=("rgb565", "rgba"), default=DEFAULT_FORMAT, help="Raw TCP request pixel format")
    parser.add_argument("--fps", type=int, default=120, help="Raw TCP request fps")
    parser.add_argument("--compress", choices=("lz4", "none"), default=DEFAULT_COMPRESS, help="Raw TCP request compression")
    parser.add_argument("--port-retry", type=int, default=10, help="device-side bind retry count")
    parser.add_argument("--ready-timeout", type=float, default=15.0, help="seconds to wait for READY=1")
    parser.add_argument("--connect-timeout", type=float, default=10.0, help="seconds to wait for local TCP connect")
    parser.add_argument("--stats-interval", type=float, default=0.3, help="bench overlay refresh interval in seconds")
    parser.add_argument("--fps-window", type=float, default=2.0, help="recent fps/throughput window")
    parser.add_argument("--max-payload-mib", type=int, default=128, help="safety limit for one frame payload")
    parser.add_argument("--max-window-width", type=int, default=1280, help="maximum displayed image width")
    parser.add_argument("--max-window-height", type=int, default=900, help="maximum displayed image height")
    parser.add_argument("--show-stderr", action="store_true", help="print raw_cast stderr logs")
    return parser.parse_args()


def load_runtime_deps(compress: str):
    try:
        import tkinter as tk
        from PIL import Image, ImageTk
    except ImportError as exc:
        raise RuntimeError(
            "Missing Python dependency: "
            f"{exc}\nInstall with: python -m pip install -r requirements.txt"
        ) from exc
    lz4_block = None
    if compress == "lz4":
        try:
            import lz4.block as lz4_block
        except ImportError as exc:
            raise RuntimeError(
                "Missing Python dependency: "
                f"{exc}\nInstall with: python -m pip install -r requirements.txt"
            ) from exc
    return tk, Image, ImageTk, lz4_block


def http_request(url: str, *, accept: str = "*/*", timeout: float = 60.0):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": accept,
            "User-Agent": HTTP_USER_AGENT,
        },
    )
    return urllib.request.urlopen(request, timeout=timeout)


def response_content_length(response) -> Optional[int]:
    value = response.headers.get("Content-Length")
    if not value:
        return None
    try:
        size = int(value)
    except ValueError:
        return None
    return size if size > 0 else None


def format_mib(size: int) -> str:
    return f"{size / 1048576.0:.1f} MiB"


def print_download_progress(label: str, downloaded: int, total: Optional[int], *, done: bool = False) -> None:
    if total:
        pct = min(downloaded * 100.0 / total, 100.0)
        text = f"[download] {label}: {pct:5.1f}% ({format_mib(downloaded)}/{format_mib(total)})"
    else:
        text = f"[download] {label}: {format_mib(downloaded)}"
    print_inline_status(text, done=done)


def copy_response_with_progress(response, out, *, label: str) -> None:
    total = response_content_length(response)
    downloaded = 0
    last_progress = 0.0
    print_download_progress(label, downloaded, total)
    while True:
        chunk = response.read(DOWNLOAD_CHUNK_SIZE)
        if not chunk:
            break
        out.write(chunk)
        downloaded += len(chunk)
        now = time.monotonic()
        if now - last_progress >= DOWNLOAD_PROGRESS_INTERVAL:
            print_download_progress(label, downloaded, total)
            last_progress = now
    print_download_progress(label, downloaded, total, done=True)


def download_url(url: str, dest: Path, *, label: str, timeout: float = 120.0) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(prefix=f"{dest.name}.", suffix=".download", dir=dest.parent, delete=False) as tmp:
            tmp_path = Path(tmp.name)
            with http_request(url, timeout=timeout) as response:
                copy_response_with_progress(response, tmp, label=label)
        tmp_path.replace(dest)
        log(f"[download] {label} saved to {dest}")
        return dest
    except Exception:
        if tmp_path is not None:
            try:
                tmp_path.unlink(missing_ok=True)
            except OSError:
                pass
        raise


def adb_not_found_error(adb: str) -> str:
    return "\n".join(
        [
            f"adb executable not found: {adb}",
            "",
            "The script can auto-download platform-tools for Windows, macOS, or Linux.",
            "If auto-download fails, install platform-tools and add its directory to PATH,",
            "or pass the full adb path, for example:",
            r"  python raw_tcp_rgb565_lz4_viewer.py --adb C:\Android\platform-tools\adb.exe",
        ]
    )


def adb_executable_name() -> str:
    return "adb.exe" if sys.platform.startswith("win") else "adb"


def examples_dir() -> Path:
    return Path(__file__).resolve().parent.parent


def bundled_platform_tools_dir() -> Path:
    return examples_dir() / "platform-tools"


def detect_platform_tools_host() -> str:
    if sys.platform.startswith(("win32", "cygwin", "msys")):
        return "windows"
    if sys.platform == "darwin":
        return "darwin"
    if sys.platform.startswith("linux"):
        return "linux"
    raise RuntimeError(f"Unsupported host platform for platform-tools auto-download: {sys.platform}")


def safe_extract(zip_path: Path, target_dir: Path) -> None:
    target_root = target_dir.resolve()
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            destination = (target_root / member.filename).resolve()
            try:
                destination.relative_to(target_root)
            except ValueError as exc:
                raise RuntimeError(f"Unsafe zip entry: {member.filename}") from exc
        archive.extractall(target_root)


def download_platform_tools() -> Optional[Path]:
    dest = bundled_platform_tools_dir()
    adb = dest / adb_executable_name()
    if adb.is_file():
        return adb

    tmp_path: Optional[Path] = None
    try:
        host = detect_platform_tools_host()
        url = PLATFORM_TOOLS_URLS[host]
        dest.parent.mkdir(parents=True, exist_ok=True)
        log(f"[adb] platform-tools missing; downloading {host} package")
        log(f"[adb] download {url}")
        with tempfile.NamedTemporaryFile(prefix="platform-tools-", suffix=".zip", delete=False) as tmp:
            tmp_path = Path(tmp.name)
            with http_request(url, timeout=60) as response:
                copy_response_with_progress(response, tmp, label=f"platform-tools {host}")
        safe_extract(tmp_path, dest.parent)
        if not adb.is_file():
            raise RuntimeError(f"downloaded platform-tools did not contain {adb.name}")
        if not sys.platform.startswith("win"):
            adb.chmod(adb.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        return adb
    except Exception as exc:
        log(f"[adb] platform-tools auto-download failed: {exc}", file=sys.stderr)
        return None
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink(missing_ok=True)
            except OSError:
                pass


def bundled_adb_candidates() -> list[Path]:
    exe = adb_executable_name()
    script_dir = Path(__file__).resolve().parent
    roots = [
        Path.cwd(),
        Path.cwd().parent,
        script_dir,
        script_dir.parent,
        script_dir.parent.parent,
    ]
    candidates: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        for rel in (Path("platform-tools") / exe, Path("examples") / "platform-tools" / exe):
            candidate = (root / rel).resolve()
            if candidate not in seen:
                seen.add(candidate)
                candidates.append(candidate)
    return candidates


def resolve_explicit_adb(adb: str) -> Optional[str]:
    path = Path(adb).expanduser()
    if path.is_file():
        return str(path.resolve())
    if sys.platform.startswith("win") and path.suffix.lower() != ".exe":
        exe_path = path.with_suffix(".exe")
        if exe_path.is_file():
            return str(exe_path.resolve())
    return shutil.which(adb)


def resolve_adb(args: argparse.Namespace) -> None:
    found: Optional[str] = None
    if args.adb == "adb":
        for candidate in bundled_adb_candidates():
            if candidate.is_file():
                found = str(candidate)
                break
        if found is None:
            downloaded = download_platform_tools()
            if downloaded is not None:
                found = str(downloaded.resolve())
        if found is None:
            found = shutil.which(args.adb)
    else:
        found = resolve_explicit_adb(args.adb)
    if found:
        args.adb = found
        log(f"[adb] using {found}")
        return
    raise FileNotFoundError(adb_not_found_error(args.adb))


def adb_cmd(args: argparse.Namespace, extra: list[str], *, use_serial: bool = True) -> list[str]:
    cmd = [args.adb]
    if use_serial and args.serial:
        cmd += ["-s", args.serial]
    return cmd + extra


def run_adb(
    args: argparse.Namespace,
    extra: list[str],
    what: str,
    check: bool = True,
    *,
    use_serial: bool = True,
) -> subprocess.CompletedProcess:
    try:
        proc = subprocess.run(adb_cmd(args, extra, use_serial=use_serial), text=True, capture_output=True)
    except FileNotFoundError as exc:
        if args.adb == "adb":
            resolve_adb(args)
            proc = subprocess.run(adb_cmd(args, extra, use_serial=use_serial), text=True, capture_output=True)
        else:
            raise FileNotFoundError(adb_not_found_error(args.adb)) from exc
    if check and proc.returncode != 0:
        detail = "\n".join(part for part in [proc.stdout.strip(), proc.stderr.strip()] if part)
        raise RuntimeError(f"{what} failed with exit code {proc.returncode}: {detail}")
    return proc


def connect_adb_address(args: argparse.Namespace) -> None:
    if not args.adb_address:
        return

    address = args.adb_address.strip()
    if not address:
        raise ValueError("--adb-address cannot be empty")

    log(f"[adb] connect {address}")
    proc = run_adb(args, ["connect", address], f"adb connect {address}", use_serial=False)
    detail = "\n".join(part for part in [proc.stdout.strip(), proc.stderr.strip()] if part)
    if detail:
        log(f"[adb] {detail}")
    lower = detail.lower()
    if any(token in lower for token in ("failed", "cannot", "unable", "refused", "timed out")):
        raise RuntimeError(f"adb connect {address} failed: {detail}")

    if not args.serial:
        args.serial = address
        log(f"[adb] serial {args.serial}")


def select_adb_device(args: argparse.Namespace) -> None:
    if args.serial:
        log(f"[adb] serial {args.serial}")
        return
    if args.adb_address:
        connect_adb_address(args)
        return

    proc = run_adb(args, ["devices"], "adb devices", use_serial=False)
    devices: list[str] = []
    for line in proc.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    if not devices:
        raise RuntimeError("No adb device found. Connect a USB device, start an emulator, or pass --adb-address HOST:PORT.")
    if len(devices) == 1:
        args.serial = devices[0]
        log(f"[adb] serial {args.serial}")
        return

    usb_devices = [serial for serial in devices if ":" not in serial]
    if len(usb_devices) == 1:
        args.serial = usb_devices[0]
        log(f"[adb] serial {args.serial} (USB)")
        return

    raise RuntimeError(
        "Multiple adb devices found: "
        + ", ".join(devices)
        + ". Pass --serial SERIAL for USB devices or --adb-address HOST:PORT for TCP ADB."
    )


def find_local_apk() -> Optional[Path]:
    cwd = Path.cwd()
    candidates = [p for p in cwd.glob("raw_cast*.apk") if p.is_file()]
    exact = cwd / "raw_cast.apk"
    if exact in candidates:
        return exact
    if candidates:
        selected = sorted(candidates, key=lambda p: (p.stat().st_mtime, p.name), reverse=True)[0]
        if len(candidates) > 1:
            names = ", ".join(p.name for p in sorted(candidates))
            log(f"[apk] multiple raw_cast*.apk files found ({names}); using newest: {selected.name}", file=sys.stderr)
        return selected
    return None


def sanitize_filename(name: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "-" for ch in name).strip(".-_")
    return cleaned or "raw_cast.apk"


def release_api_url(repo: str) -> str:
    return f"{GITHUB_API}/repos/{repo}/releases/latest"


def fetch_latest_release(repo: str) -> dict:
    url = release_api_url(repo)
    log(f"[apk] query latest release: {url}")
    with http_request(url, accept="application/vnd.github+json", timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def apk_asset_score(asset: dict) -> tuple[int, int, str]:
    name = str(asset.get("name") or "")
    lower = name.lower()
    size = int(asset.get("size") or 0)
    score = 0
    if lower.endswith(".apk"):
        score += 100
    if lower.startswith("raw_cast") or lower.startswith("raw-cast"):
        score += 40
    if "universal" in lower:
        score += 20
    if "release" in lower:
        score += 10
    if "debug" in lower:
        score -= 80
    if "unsigned" in lower:
        score -= 30
    return score, size, name


def choose_apk_asset(release: dict) -> dict:
    assets = release.get("assets") or []
    candidates = [
        asset for asset in assets
        if str(asset.get("name") or "").lower().endswith(".apk")
        and asset.get("browser_download_url")
    ]
    if not candidates:
        tag = release.get("tag_name") or "latest"
        raise RuntimeError(f"No .apk asset found in {RAW_CAST_RELEASE_REPO} release {tag}")
    return max(candidates, key=apk_asset_score)


def apk_dest_for_release_asset(release: dict, asset: dict) -> Path:
    tag = sanitize_filename(str(release.get("tag_name") or "latest"))
    asset_name = sanitize_filename(str(asset.get("name") or "raw_cast.apk"))
    if not asset_name.lower().startswith("raw_cast"):
        asset_name = f"raw_cast-{tag}.apk"
    return Path.cwd() / asset_name


def local_apk_matches_asset(local: Path, asset: dict, dest: Path) -> bool:
    asset_name = sanitize_filename(str(asset.get("name") or local.name))
    return local.name == dest.name or local.name == asset_name


def remove_local_raw_cast_apks(keep: Optional[Path] = None) -> None:
    keep_resolved = keep.resolve() if keep is not None and keep.exists() else None
    for apk in sorted(Path.cwd().glob("raw_cast*.apk")):
        if not apk.is_file():
            continue
        if keep_resolved is not None and apk.resolve() == keep_resolved:
            continue
        log(f"[apk] remove outdated local APK {apk.name}")
        apk.unlink()


def download_release_apk(release: dict, asset: dict, dest: Path, *, reason: str) -> Path:
    if dest.is_file():
        if local_apk_matches_asset(dest, asset, dest):
            log(f"[apk] using downloaded APK {dest.resolve()}")
            return dest
        log(f"[apk] downloaded APK name differs from latest asset: {dest.name} latest={asset.get('name')}")
        dest.unlink()
    log(f"[apk] {reason}; downloading latest release APK")
    log(f"[apk] release={release.get('tag_name') or 'latest'} asset={asset.get('name')}")
    return download_url(str(asset["browser_download_url"]), dest, label="APK", timeout=180)


def prepare_apk() -> Path:
    try:
        release = fetch_latest_release(RAW_CAST_RELEASE_REPO)
        asset = choose_apk_asset(release)
        dest = apk_dest_for_release_asset(release, asset)
        local = find_local_apk()
        if local is not None and local_apk_matches_asset(local, asset, dest):
            remove_local_raw_cast_apks(keep=local)
            log(f"[apk] using latest local {local.resolve()}")
            return local
        if local is not None:
            log(
                f"[apk] local APK differs from latest release asset; "
                f"local={local.name} latest={asset.get('name')}"
            )
            remove_local_raw_cast_apks()
        return download_release_apk(release, asset, dest, reason="local raw_cast APK is missing or outdated")
    except Exception as exc:
        raise FileNotFoundError(
            "\n".join(
                [
                    f"Could not prepare latest raw_cast APK in current working directory: {Path.cwd()}",
                    f"Latest release APK check/download from {RAW_CAST_RELEASE_REPO} failed: {exc}",
                    "",
                    "Put a raw_cast APK in the current directory with a name like raw_cast.apk,",
                    "or check your network access to GitHub and run the script again.",
                ]
            )
        ) from exc


def start_raw_cast(args: argparse.Namespace, apk: Path):
    log(f"[apk] using {apk.resolve()}")

    log(f"[adb] push {apk} -> {args.remote_apk}")
    run_adb(args, ["push", str(apk), args.remote_apk], "adb push")

    shell_args = [
        "shell",
        f"CLASSPATH={args.remote_apk}",
        "app_process",
        "/",
        "ink.mol.raw_cast.Main",
        f"--port={args.http}",
        f"--tcp={args.tcp}",
        f"--port-retry={args.port_retry}",
    ]
    log("[adb] start raw_cast")
    try:
        proc = subprocess.Popen(
            adb_cmd(args, shell_args),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
    except FileNotFoundError as exc:
        if args.adb == "adb":
            resolve_adb(args)
            proc = subprocess.Popen(
                adb_cmd(args, shell_args),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
        else:
            raise FileNotFoundError(adb_not_found_error(args.adb)) from exc
    if proc.stdout is None or proc.stderr is None:
        raise RuntimeError("failed to capture raw_cast stdout/stderr")
    stdout = StreamPump(proc.stdout, "raw_cast-stdout")
    stderr = StreamPump(proc.stderr, "raw_cast-stderr", echo=args.show_stderr)
    status = wait_for_ready(proc, stdout, stderr, args.ready_timeout)
    return proc, stderr, status


def wait_for_ready(
    proc: subprocess.Popen,
    stdout: StreamPump,
    stderr: StreamPump,
    timeout: float,
) -> dict[str, str]:
    status: dict[str, str] = {}
    deadline = time.perf_counter() + timeout
    while time.perf_counter() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(
                "raw_cast exited before READY.\n"
                f"stdout tail:\n{format_tail(stdout.tail)}\n"
                f"stderr tail:\n{format_tail(stderr.tail)}"
            )
        try:
            line = stdout.lines.get(timeout=0.1).strip()
        except queue.Empty:
            continue
        if not line:
            continue
        log(f"[raw_cast] {line}")
        if line.startswith("PID="):
            status["PID"] = line.split("=", 1)[1]
        elif line.startswith("BIND:"):
            key, value = line.split("=", 1)
            status[key] = value
        elif line.startswith("READY="):
            status["READY"] = line.split("=", 1)[1]
            if status["READY"] == "1":
                return status
            raise RuntimeError(
                "raw_cast reported READY=0.\n"
                f"stdout tail:\n{format_tail(stdout.tail)}\n"
                f"stderr tail:\n{format_tail(stderr.tail)}"
            )
    raise TimeoutError(
        f"raw_cast did not print READY within {timeout:.1f}s.\n"
        f"stdout tail:\n{format_tail(stdout.tail)}\n"
        f"stderr tail:\n{format_tail(stderr.tail)}"
    )


def format_tail(lines: Iterable[str]) -> str:
    text = "\n".join(lines)
    return text if text else "<empty>"


def bind_port(status: dict[str, str], name: str) -> Optional[int]:
    value = status.get(f"BIND:{name}")
    if not value or value == "FAILED":
        return None
    try:
        return int(value)
    except ValueError:
        return None


def setup_forward(args: argparse.Namespace, local_port: int, device_port: int) -> None:
    run_adb(args, ["forward", f"tcp:{local_port}", f"tcp:{device_port}"], f"adb forward tcp:{local_port}")
    log(f"[adb] forward tcp:{local_port} -> tcp:{device_port}")


def remove_forward(args: argparse.Namespace, local_port: int) -> None:
    run_adb(args, ["forward", "--remove", f"tcp:{local_port}"], f"adb forward --remove tcp:{local_port}", check=False)


def terminate_process(proc: Optional[subprocess.Popen]) -> None:
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=3.0)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=3.0)


def connect_with_retry(host: str, port: int, timeout: float) -> socket.socket:
    deadline = time.perf_counter() + timeout
    last_error: Optional[BaseException] = None
    while time.perf_counter() < deadline:
        try:
            return socket.create_connection((host, port), timeout=2.0)
        except OSError as exc:
            last_error = exc
            time.sleep(0.1)
    raise TimeoutError(f"could not connect to {host}:{port} within {timeout:.1f}s: {last_error}")


def read_exact(sock: socket.socket, size: int, stop_event: Optional[threading.Event] = None) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        if stop_event is not None and stop_event.is_set():
            raise InterruptedError("stream reader stopped")
        try:
            part = sock.recv(size - len(chunks))
        except socket.timeout:
            if stop_event is not None and stop_event.is_set():
                raise InterruptedError("stream reader stopped")
            continue
        if not part:
            raise EOFError("socket closed while reading frame")
        chunks.extend(part)
    return bytes(chunks)


def parse_banner(data: bytes) -> int:
    if len(data) != 8 or data[:4] != MAGIC:
        raise ValueError(f"invalid banner: {data!r}")
    version = struct.unpack_from("<I", data, 4)[0]
    if version != PROTOCOL_VERSION:
        raise ValueError(f"unsupported protocol version: {version}")
    return version


def parse_frame_header(data: bytes, max_payload_bytes: int, expect_lz4: bool, expected_format: int, format_name: str) -> FrameHeader:
    if len(data) != FRAME_HEADER_SIZE or data[:4] != MAGIC:
        raise ValueError("invalid RC01 frame magic")
    _, version, flags, fmt, seq, width, height, stride, payload_size, timestamp_ms = HEADER_STRUCT.unpack(data)
    if version != 1:
        raise ValueError(f"unsupported frame version: {version}")
    if fmt != expected_format:
        raise ValueError(f"unexpected frame format id {fmt}; this example requests {format_name} id {expected_format}")
    is_lz4 = (flags & FLAG_LZ4) != 0
    if expect_lz4 and not is_lz4:
        raise ValueError("frame is not LZ4-compressed; this example requests compress=lz4")
    if not expect_lz4 and is_lz4:
        raise ValueError("frame is LZ4-compressed; this example requests compress=none")
    if payload_size <= 0 or payload_size > max_payload_bytes:
        raise ValueError(f"invalid payload size: {payload_size}")
    if width <= 0 or height <= 0:
        raise ValueError(f"invalid frame size: {width}x{height}")
    return FrameHeader(version, flags, fmt, seq, width, height, stride, payload_size, timestamp_ms)


def raw_payload_to_image(Image, fmt: str, width: int, height: int, payload: bytes):
    if fmt == "rgb565":
        return Image.frombytes("RGB", (width, height), payload, "raw", "BGR;16")
    if fmt == "rgba":
        return Image.frombytes("RGBA", (width, height), payload, "raw", "RGBA")
    raise ValueError(f"unsupported viewer format: {fmt}")


def reader_loop(args, image_queue, error_queue, stop_event, bench_state: BenchState, Image, lz4_block) -> None:
    request_line = f"format={args.format} fps={args.fps} width=0 height=0 compress={args.compress}\n"
    metrics = Metrics(args.fps_window)
    max_payload_bytes = args.max_payload_mib * 1024 * 1024
    warmup_seen = 0
    last_bench_update = 0.0
    expect_lz4 = args.compress == "lz4"
    expected_format = FORMAT_IDS[args.format]
    bytes_per_pixel = FORMAT_BYTES_PER_PIXEL[args.format]
    bench_label = f"bench {args.format}/{args.compress}"
    try:
        with connect_with_retry("127.0.0.1", args.tcp, args.connect_timeout) as sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(0.5)
            log(f"[raw-tcp] connected 127.0.0.1:{args.tcp}")
            sock.sendall(request_line.encode("ascii"))
            version = parse_banner(read_exact(sock, 8, stop_event))
            log(f"[raw-tcp] protocol version={version}; request={request_line.strip()}")
            log(f"[bench] warmup frames={WARMUP_FRAMES}")
            bench_state.set_overlay(f"{bench_label}\nwarmup 0/{WARMUP_FRAMES}")
            while not stop_event.is_set():
                header = parse_frame_header(
                    read_exact(sock, FRAME_HEADER_SIZE, stop_event),
                    max_payload_bytes,
                    expect_lz4,
                    expected_format,
                    args.format,
                )
                payload = read_exact(sock, header.payload_size, stop_event)
                decoded_size = header.width * header.height * bytes_per_pixel
                if expect_lz4:
                    if lz4_block is None:
                        raise RuntimeError("lz4 module was not loaded")
                    raw_pixels = lz4_block.decompress(payload, uncompressed_size=decoded_size)
                else:
                    raw_pixels = payload
                if len(raw_pixels) != decoded_size:
                    raise ValueError(f"decoded size mismatch: {len(raw_pixels)} != {decoded_size}")
                image = raw_payload_to_image(Image, args.format, header.width, header.height, raw_pixels)
                now = time.perf_counter()
                if warmup_seen < WARMUP_FRAMES:
                    warmup_seen += 1
                    bench_state.set_overlay(f"{bench_label}\nwarmup {warmup_seen}/{WARMUP_FRAMES}")
                    if warmup_seen == WARMUP_FRAMES:
                        metrics.reset(now)
                    continue
                metrics.observe(header, len(payload), len(raw_pixels), now)
                summary = metrics.summary()
                bench_state.set_summary_only(summary)
                if now - last_bench_update >= args.stats_interval:
                    bench_state.set_summary(summary, metrics.overlay_text(bench_label))
                    last_bench_update = now
                put_latest(image_queue, FrameItem(image=image, seq=header.seq))
    except Exception as exc:
        if not stop_event.is_set():
            error_queue.put(exc)
        stop_event.set()


def put_latest(q: "queue.Queue[FrameItem]", item: FrameItem) -> None:
    try:
        q.put_nowait(item)
        return
    except queue.Full:
        pass
    try:
        q.get_nowait()
    except queue.Empty:
        pass
    try:
        q.put_nowait(item)
    except queue.Full:
        pass


def run_viewer(args, ImageTk, tk, image_queue, error_queue, stop_event, bench_state: BenchState) -> Optional[BaseException]:
    root = tk.Tk()
    root.title(getattr(args, "window_title", f"raw_cast Raw TCP {args.format}/{args.compress}"))
    label = tk.Label(root, background="black")
    label.pack(fill="both", expand=True)
    overlay = tk.Label(
        root,
        text=bench_state.overlay(),
        foreground="#f5f5f5",
        background="#101010",
        font=("Consolas", 9),
        justify="left",
        anchor="ne",
        padx=6,
        pady=4,
    )
    overlay.place(relx=1.0, x=-8, y=8, anchor="ne")
    state = {"photo": None, "closed": False, "error": None, "after_id": None, "interrupted": False}

    def on_close() -> None:
        if state["closed"]:
            return
        state["closed"] = True
        stop_event.set()
        finish_inline_status()
        after_id = state.get("after_id")
        if after_id is not None:
            try:
                root.after_cancel(after_id)
            except tk.TclError:
                pass
            state["after_id"] = None
        try:
            root.quit()
        except tk.TclError:
            pass
        try:
            root.destroy()
        except tk.TclError:
            pass

    def handle_callback_exception(exc_type, exc, tb) -> None:
        if issubclass(exc_type, KeyboardInterrupt):
            state["interrupted"] = True
            state["error"] = None
            on_close()
            return
        if issubclass(exc_type, tk.TclError) and state["closed"]:
            return
        state["error"] = exc
        log(f"[error] Tk callback failed: {exc}", file=sys.stderr)
        on_close()

    def poll() -> None:
        state["after_id"] = None
        try:
            if not error_queue.empty():
                exc = error_queue.get_nowait()
                state["error"] = exc
                log(f"[error] {exc}", file=sys.stderr)
                on_close()
                return
            latest = None
            while True:
                try:
                    latest = image_queue.get_nowait()
                except queue.Empty:
                    break
            if latest is not None:
                image = latest.image.copy()
                image.thumbnail((args.max_window_width, args.max_window_height))
                photo = ImageTk.PhotoImage(image)
                state["photo"] = photo
                label.configure(image=photo)
            overlay.configure(text=bench_state.overlay())
            overlay.lift()
            if not stop_event.is_set():
                state["after_id"] = root.after(15, poll)
            else:
                on_close()
        except KeyboardInterrupt:
            state["interrupted"] = True
            state["error"] = None
            on_close()
        except tk.TclError as exc:
            if not state["closed"]:
                state["error"] = exc
                log(f"[error] Tk failed: {exc}", file=sys.stderr)
                on_close()

    def request_stop(_signum=None, _frame=None) -> None:
        state["interrupted"] = True
        state["error"] = None
        stop_event.set()
        finish_inline_status()
        try:
            root.after(0, on_close)
        except tk.TclError:
            on_close()

    previous_sigint = None
    try:
        previous_sigint = signal.getsignal(signal.SIGINT)
        signal.signal(signal.SIGINT, request_stop)
    except (ValueError, OSError):
        previous_sigint = None

    root.report_callback_exception = handle_callback_exception
    root.protocol("WM_DELETE_WINDOW", on_close)
    state["after_id"] = root.after(15, poll)
    try:
        root.mainloop()
    except KeyboardInterrupt:
        request_stop()
    finally:
        if previous_sigint is not None:
            try:
                signal.signal(signal.SIGINT, previous_sigint)
            except (ValueError, OSError):
                pass
        finish_inline_status()
    if state["interrupted"]:
        raise KeyboardInterrupt
    return state["error"]


def log_final_bench(bench_state: BenchState) -> None:
    summary = bench_state.summary()
    if summary:
        log(f"[bench] {summary}")


def main() -> int:
    args = parse_args()
    raw_proc: Optional[subprocess.Popen] = None
    forwarded_ports: list[int] = []
    stop_event = threading.Event()
    image_queue: "queue.Queue[FrameItem]" = queue.Queue(maxsize=1)
    error_queue: "queue.Queue[BaseException]" = queue.Queue()
    bench_state = BenchState()
    reader: Optional[threading.Thread] = None

    try:
        log(f"[raw_cast-python] starting Raw TCP {args.format}/{args.compress} viewer")
        log(f"[cwd] {Path.cwd()}")
        log("[prepare] checking adb")
        resolve_adb(args)
        select_adb_device(args)
        log("[prepare] checking APK")
        apk = prepare_apk()
        tk, Image, ImageTk, lz4_block = load_runtime_deps(args.compress)
        raw_proc, _stderr, status = start_raw_cast(args, apk)
        tcp_device_port = bind_port(status, "TCP")
        if tcp_device_port is None:
            raise RuntimeError(f"Raw TCP failed to bind: {status}")
        setup_forward(args, args.tcp, tcp_device_port)
        forwarded_ports.append(args.tcp)

        http_device_port = bind_port(status, "HTTP")
        if args.http > 0 and http_device_port is not None:
            setup_forward(args, args.http, http_device_port)
            forwarded_ports.append(args.http)
            log("[http] debug endpoints:")
            log(f"  http://127.0.0.1:{args.http}/preview")
            log(f"  http://127.0.0.1:{args.http}/screenshot?format=png")
            log(f"  http://127.0.0.1:{args.http}/screenshot?format=webp&quality=80")
        elif args.http > 0:
            log("[http] HTTP debug port did not bind; Raw TCP will still run.", file=sys.stderr)

        reader = threading.Thread(
            target=reader_loop,
            args=(args, image_queue, error_queue, stop_event, bench_state, Image, lz4_block),
            name="raw-tcp-reader",
            daemon=True,
        )
        reader.start()
        viewer_error = run_viewer(args, ImageTk, tk, image_queue, error_queue, stop_event, bench_state)
        finish_inline_status()
        log_final_bench(bench_state)
        if viewer_error is not None:
            return 1
        log("[exit] viewer closed")
        return 0
    except KeyboardInterrupt:
        finish_inline_status()
        log_final_bench(bench_state)
        log("\n[exit] interrupted")
        return 130
    except Exception as exc:
        finish_inline_status()
        log_final_bench(bench_state)
        log(f"[error] {exc}", file=sys.stderr)
        return 1
    finally:
        finish_inline_status()
        stop_event.set()
        if reader is not None:
            reader.join(timeout=2.0)
        for port in forwarded_ports:
            remove_forward(args, port)
        terminate_process(raw_proc)


if __name__ == "__main__":
    raise SystemExit(main())
