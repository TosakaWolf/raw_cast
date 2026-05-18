#!/usr/bin/env python3
"""
ARGB_8888 raw screenshot benchmark for VisoTC/DroidCast_raw.

The script auto-detects DroidCast_raw*.apk in the current directory. If no APK
is present, it downloads DroidCast_raw.apk from VisoTC/MaaAssistantArknights.
Then run for example:

    python droidcast_raw_argb8888_bench.py --adb-address 127.0.0.1:16384

The script starts DroidCast_raw through app_process, forwards the HTTP port,
then repeatedly requests /screenshot?format=rgb8888, displays frames with
Tkinter, shows benchmark stats in the top-right overlay, and prints the final
summary when the window closes.
"""

from __future__ import annotations

import argparse
import collections
import queue
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path
from typing import Deque, Iterable, Optional

from raw_tcp_rgb565_lz4_viewer import (
    BenchState,
    FrameItem,
    StreamPump,
    adb_cmd,
    connect_adb_address,
    download_url,
    finish_inline_status,
    http_request,
    load_runtime_deps,
    log,
    percentile,
    put_latest,
    remove_forward,
    resolve_adb,
    run_viewer,
    run_adb,
    select_adb_device,
    setup_forward,
    terminate_process,
)


DROIDCAST_REPO = "VisoTC/DroidCast_raw"
DROIDCAST_APK_DOWNLOAD_URL = (
    "https://raw.githubusercontent.com/VisoTC/MaaAssistantArknights/"
    "droidcast-screenshot-backend/resource/droidcast/DroidCast_raw.apk"
)
DROIDCAST_APK_FILENAME = "DroidCast_raw.apk"
DEFAULT_REMOTE_APK = "/data/local/tmp/DroidCast_raw.apk"
WARMUP_SAMPLES = 2
ARGB8888_BPP = 4


class HttpBenchMetrics:
    def __init__(self, fps_window: float) -> None:
        self.fps_window = fps_window
        self.reset(time.perf_counter())

    def reset(self, now: float) -> None:
        self.start = now
        self.samples = 0
        self.first_ms: Optional[float] = None
        self.durations_ms: Deque[float] = collections.deque(maxlen=5000)
        self.frame_times: Deque[float] = collections.deque(maxlen=1000)
        self.byte_events: Deque[tuple[float, int]] = collections.deque(maxlen=1000)
        self.total_bytes = 0
        self.last_width = 0
        self.last_height = 0
        self.last_payload_size = 0

    def observe(self, duration_ms: float, payload_size: int, width: int, height: int, now: float) -> None:
        if self.samples == 0:
            self.first_ms = duration_ms
        self.samples += 1
        self.durations_ms.append(duration_ms)
        self.frame_times.append(now)
        self.byte_events.append((now, payload_size))
        self.total_bytes += payload_size
        self.last_width = width
        self.last_height = height
        self.last_payload_size = payload_size
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
        avg_fps = self.samples / elapsed
        recent_fps = self._recent_fps()
        avg_mib = (self.total_bytes / 1048576.0) / elapsed
        recent_mib = self._recent_mib(now)
        first = self.first_ms or 0.0
        p50 = percentile(self.durations_ms, 50)
        p95 = percentile(self.durations_ms, 95)
        decoded_mib = (self.last_width * self.last_height * ARGB8888_BPP) / 1048576.0
        return (
            f"samples={self.samples} "
            f"fps={recent_fps:.2f}/{avg_fps:.2f} "
            f"first={first:.1f}ms p50={p50:.1f}ms p95={p95:.1f}ms "
            f"throughput={recent_mib:.2f}/{avg_mib:.2f}MiB/s "
            f"size={self.last_width}x{self.last_height} "
            f"payload={self.last_payload_size / 1048576.0:.2f}MiB "
            f"decoded={decoded_mib:.2f}MiB"
        )

    def overlay_text(self) -> str:
        now = time.perf_counter()
        self._trim_recent(now)
        elapsed = max(now - self.start, 1e-6)
        avg_fps = self.samples / elapsed
        recent_fps = self._recent_fps()
        avg_mib = (self.total_bytes / 1048576.0) / elapsed
        recent_mib = self._recent_mib(now)
        first = self.first_ms or 0.0
        p50 = percentile(self.durations_ms, 50)
        p95 = percentile(self.durations_ms, 95)
        decoded_mib = (self.last_width * self.last_height * ARGB8888_BPP) / 1048576.0
        return "\n".join(
            [
                "bench DroidCast_raw argb8888",
                f"samples {self.samples}",
                f"fps {recent_fps:.2f} / avg {avg_fps:.2f}",
                f"first {first:.1f} ms",
                f"p50 {p50:.1f} ms  p95 {p95:.1f} ms",
                f"net {recent_mib:.2f} / avg {avg_mib:.2f} MiB/s",
                f"size {self.last_width}x{self.last_height}",
                f"payload {self.last_payload_size / 1048576.0:.2f} MiB",
                f"decoded {decoded_mib:.2f} MiB",
            ]
        )

    def _recent_fps(self) -> float:
        if len(self.frame_times) < 2:
            return 0.0
        span = max(self.frame_times[-1] - self.frame_times[0], 1e-6)
        return (len(self.frame_times) - 1) / span

    def _recent_mib(self, now: float) -> float:
        if not self.byte_events:
            return 0.0
        span = min(self.fps_window, max(now - self.byte_events[0][0], 1e-6))
        return (sum(size for _, size in self.byte_events) / 1048576.0) / span


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Benchmark VisoTC/DroidCast_raw /screenshot?format=rgb8888 raw ARGB_8888 output.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--apk", help="DroidCast_raw APK path; auto-detects DroidCast_raw*.apk when omitted")
    parser.add_argument("--remote-apk", default=DEFAULT_REMOTE_APK, help="device APK path")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument(
        "--adb-address",
        help="run 'adb connect HOST:PORT' before starting; also used as --serial when --serial is omitted",
    )
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--port", type=int, default=53516, help="local and device HTTP port")
    parser.add_argument("--samples", type=int, default=0, help="measured samples after warmup; 0 means run until the window closes")
    parser.add_argument("--warmup", type=int, default=WARMUP_SAMPLES, help="warmup requests before timing")
    parser.add_argument("--width", type=int, default=0, help="requested screenshot width; 0 lets DroidCast_raw decide")
    parser.add_argument("--height", type=int, default=0, help="requested screenshot height; 0 lets DroidCast_raw decide")
    parser.add_argument("--ready-timeout", type=float, default=15.0, help="seconds to wait for HTTP readiness")
    parser.add_argument("--request-timeout", type=float, default=10.0, help="HTTP request timeout in seconds")
    parser.add_argument("--stats-interval", type=float, default=0.3, help="progress refresh interval in seconds")
    parser.add_argument("--fps-window", type=float, default=2.0, help="recent fps/throughput window")
    parser.add_argument("--max-payload-mib", type=int, default=256, help="safety limit for one raw payload")
    parser.add_argument("--max-window-width", type=int, default=1280, help="maximum displayed image width")
    parser.add_argument("--max-window-height", type=int, default=900, help="maximum displayed image height")
    parser.add_argument("--show-stderr", action="store_true", help="print DroidCast_raw stderr logs")
    return parser.parse_args()


def apk_candidates() -> list[Path]:
    patterns = (
        "DroidCast_raw*.apk",
        "droidcast_raw*.apk",
        "DroidCastRaw*.apk",
        "droidcastraw*.apk",
    )
    roots = [Path.cwd(), Path(__file__).resolve().parent]
    candidates: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        for pattern in patterns:
            for candidate in root.glob(pattern):
                resolved = candidate.resolve()
                if resolved not in seen and resolved.is_file():
                    seen.add(resolved)
                    candidates.append(resolved)
    return sorted(candidates, key=lambda path: path.stat().st_mtime, reverse=True)


def prepare_apk(args: argparse.Namespace) -> Path:
    if args.apk:
        apk = Path(args.apk).expanduser().resolve()
        if not apk.is_file():
            raise FileNotFoundError(f"DroidCast_raw APK not found: {apk}")
        return apk
    candidates = apk_candidates()
    if candidates:
        return candidates[0]
    dest = Path.cwd() / DROIDCAST_APK_FILENAME
    log("[apk] no local DroidCast_raw*.apk found; downloading DroidCast_raw.apk")
    log(f"[apk] source={DROIDCAST_APK_DOWNLOAD_URL}")
    try:
        return download_url(DROIDCAST_APK_DOWNLOAD_URL, dest, label="DroidCast_raw APK", timeout=180)
    except Exception as exc:
        raise FileNotFoundError(
            "\n".join(
                [
                    f"No DroidCast_raw*.apk found in {Path.cwd()}",
                    f"Auto-download from https://github.com/VisoTC/MaaAssistantArknights failed: {exc}",
                    "",
                    "Put DroidCast_raw.apk in the current directory, or pass --apk PATH.",
                ]
            )
        ) from exc


def start_droidcast(args: argparse.Namespace, apk: Path) -> tuple[subprocess.Popen, StreamPump, StreamPump]:
    log(f"[apk] using {apk}")
    log(f"[adb] push {apk} -> {args.remote_apk}")
    run_adb(args, ["push", str(apk), args.remote_apk], "adb push")

    shell_args = [
        "shell",
        f"CLASSPATH={args.remote_apk}",
        "app_process",
        "/",
        "ink.mol.droidcast_raw.Main",
        f"--port={args.port}",
    ]
    log("[adb] start DroidCast_raw")
    proc = subprocess.Popen(
        adb_cmd(args, shell_args),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    if proc.stdout is None or proc.stderr is None:
        raise RuntimeError("failed to capture DroidCast_raw stdout/stderr")
    stdout = StreamPump(proc.stdout, "droidcast-stdout")
    stderr = StreamPump(proc.stderr, "droidcast-stderr", echo=args.show_stderr)
    return proc, stdout, stderr


def screenshot_url(args: argparse.Namespace) -> str:
    query = {"format": "rgb8888"}
    if args.width > 0 and args.height > 0:
        query["width"] = str(args.width)
        query["height"] = str(args.height)
    return f"http://127.0.0.1:{args.port}/screenshot?{urllib.parse.urlencode(query)}"


def read_int_header(headers, name: str) -> Optional[int]:
    value = headers.get(name)
    if not value:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def fetch_argb8888(url: str, args: argparse.Namespace) -> tuple[int, int, bytes]:
    with http_request(url, accept="application/octet-stream", timeout=args.request_timeout) as response:
        payload = response.read()
        width = read_int_header(response.headers, "X-Screenshot-Width") or args.width
        height = read_int_header(response.headers, "X-Screenshot-Height") or args.height
        bpp = read_int_header(response.headers, "X-Screenshot-Bytes-Per-Pixel") or ARGB8888_BPP
        fmt = response.headers.get("X-Screenshot-Format", "")
    max_payload = args.max_payload_mib * 1024 * 1024
    if len(payload) <= 0 or len(payload) > max_payload:
        raise ValueError(f"invalid payload size: {len(payload)}")
    if bpp != ARGB8888_BPP:
        raise ValueError(f"unexpected bytes per pixel: {bpp}; expected {ARGB8888_BPP} for ARGB_8888")
    if width <= 0 or height <= 0:
        raise ValueError("missing X-Screenshot-Width/Height headers; pass --width and --height if needed")
    expected_size = width * height * ARGB8888_BPP
    if len(payload) != expected_size:
        raise ValueError(f"payload size mismatch: {len(payload)} != {expected_size} ({width}x{height}x4, format={fmt})")
    return width, height, payload


def wait_for_ready(url: str, args: argparse.Namespace, proc: subprocess.Popen, stdout: StreamPump, stderr: StreamPump) -> None:
    deadline = time.perf_counter() + args.ready_timeout
    last_error: Optional[BaseException] = None
    while time.perf_counter() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(
                "DroidCast_raw exited before HTTP became ready.\n"
                f"stdout tail:\n{format_tail(stdout.tail)}\n"
                f"stderr tail:\n{format_tail(stderr.tail)}"
            )
        try:
            fetch_argb8888(url, args)
            return
        except Exception as exc:
            last_error = exc
            time.sleep(0.1)
    raise TimeoutError(f"DroidCast_raw HTTP endpoint was not ready within {args.ready_timeout:.1f}s: {last_error}")


def format_tail(lines: Iterable[str]) -> str:
    text = "\n".join(lines)
    return text if text else "<empty>"


def reader_loop(
    url: str,
    args: argparse.Namespace,
    Image,
    image_queue: "queue.Queue[FrameItem]",
    error_queue: "queue.Queue[BaseException]",
    stop_event: threading.Event,
    bench_state: BenchState,
) -> None:
    log(f"[http] benchmark endpoint: {url}")
    log(f"[bench] warmup samples={args.warmup}")
    bench_state.set_overlay(f"bench DroidCast_raw argb8888\nwarmup 0/{args.warmup}")
    metrics = HttpBenchMetrics(args.fps_window)
    last_bench_update = 0.0
    try:
        for i in range(args.warmup):
            if stop_event.is_set():
                return
            fetch_argb8888(url, args)
            log(f"[bench] warmup {i + 1}/{args.warmup}")
            bench_state.set_overlay(f"bench DroidCast_raw argb8888\nwarmup {i + 1}/{args.warmup}")
        metrics.reset(time.perf_counter())

        measured = 0
        while not stop_event.is_set() and (args.samples <= 0 or measured < args.samples):
            start = time.perf_counter()
            width, height, payload = fetch_argb8888(url, args)
            now = time.perf_counter()
            elapsed_ms = (now - start) * 1000.0
            image = Image.frombytes("RGBA", (width, height), payload, "raw", "RGBA")
            metrics.observe(elapsed_ms, len(payload), width, height, now)
            measured += 1
            summary = metrics.summary()
            bench_state.set_summary_only(summary)
            if now - last_bench_update >= args.stats_interval:
                bench_state.set_summary(summary, metrics.overlay_text())
                last_bench_update = now
            put_latest(image_queue, FrameItem(image=image, seq=measured))
        stop_event.set()
    except Exception as exc:
        if not stop_event.is_set():
            error_queue.put(exc)
        stop_event.set()


def log_final_bench(bench_state: BenchState) -> None:
    summary = bench_state.summary()
    if summary:
        log(f"[bench] {summary}")


def main() -> int:
    args = parse_args()
    args.format = "DroidCast_raw argb8888"
    args.compress = "http"
    args.window_title = "DroidCast_raw ARGB_8888"
    proc: Optional[subprocess.Popen] = None
    forwarded = False
    stop_event = threading.Event()
    image_queue: "queue.Queue[FrameItem]" = queue.Queue(maxsize=1)
    error_queue: "queue.Queue[BaseException]" = queue.Queue()
    bench_state = BenchState()
    reader: Optional[threading.Thread] = None
    try:
        log("[droidcast-raw-python] starting ARGB_8888 screenshot viewer/benchmark")
        log(f"[repo] https://github.com/{DROIDCAST_REPO}")
        log(f"[cwd] {Path.cwd()}")
        log("[prepare] checking adb")
        resolve_adb(args)
        select_adb_device(args)
        apk = prepare_apk(args)
        tk, Image, ImageTk, _lz4_block = load_runtime_deps("none")
        proc, stdout, stderr = start_droidcast(args, apk)
        setup_forward(args, args.port, args.port)
        forwarded = True
        url = screenshot_url(args)
        wait_for_ready(url, args, proc, stdout, stderr)
        reader = threading.Thread(
            target=reader_loop,
            args=(url, args, Image, image_queue, error_queue, stop_event, bench_state),
            name="droidcast-argb8888-reader",
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
        if forwarded:
            remove_forward(args, args.port)
        terminate_process(proc)


if __name__ == "__main__":
    raise SystemExit(main())
