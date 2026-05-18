#!/usr/bin/env python3
"""ADB stdout raw-pixel viewer for raw_cast.

Unlike Raw TCP, stdout mode does not accept a request line. Capture format,
FPS, and compression are launch options passed to app_process, and stdout is a
pure binary stream: 8-byte RC01 banner followed by RC01 frames.
"""

from __future__ import annotations

import argparse
import queue
import shlex
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from raw_tcp_rgb565_lz4_viewer import (
    FORMAT_BYTES_PER_PIXEL,
    FORMAT_IDS,
    FRAME_HEADER_SIZE,
    BenchState,
    FrameItem,
    Metrics,
    adb_cmd,
    finish_inline_status,
    load_runtime_deps,
    log,
    log_final_bench,
    parse_banner,
    parse_frame_header,
    prepare_apk,
    put_latest,
    raw_payload_to_image,
    resolve_adb,
    run_adb,
    run_viewer,
    select_adb_device,
    terminate_process,
)


DEFAULT_FORMAT = "rgb565"
DEFAULT_COMPRESS = "lz4"


class BinaryStreamPump:
    def __init__(self, stream, name: str, echo: bool = False) -> None:
        self.name = name
        self.echo = echo
        self.tail: list[str] = []
        self.thread = threading.Thread(target=self._run, args=(stream,), name=f"{name}-pump", daemon=True)
        self.thread.start()

    def _run(self, stream) -> None:
        try:
            for raw in iter(stream.readline, b""):
                clean = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                self.tail.append(clean)
                self.tail = self.tail[-80:]
                if self.echo:
                    log(f"[{self.name}] {clean}", file=sys.stderr)
        except Exception as exc:
            self.tail.append(f"<pump error: {exc}>")
            self.tail = self.tail[-80:]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Start raw_cast in --mode=stdout and view raw-pixel frames with Tkinter.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--remote-apk", default="/data/local/tmp/raw_cast.apk", help="device APK path")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument(
        "--adb-address",
        help="run 'adb connect HOST:PORT' before starting; also used as --serial when --serial is omitted",
    )
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--format", choices=("rgb565", "rgba"), default=DEFAULT_FORMAT, help="stdout pixel format")
    parser.add_argument("--fps", type=int, default=120, help="capture fps")
    parser.add_argument("--compress", choices=("lz4", "none"), default=DEFAULT_COMPRESS, help="stdout compression")
    parser.add_argument("--stats-interval", type=float, default=0.3, help="bench overlay refresh interval in seconds")
    parser.add_argument("--fps-window", type=float, default=2.0, help="recent fps/throughput window")
    parser.add_argument("--max-payload-mib", type=int, default=128, help="safety limit for one frame payload")
    parser.add_argument("--max-window-width", type=int, default=1280, help="maximum displayed image width")
    parser.add_argument("--max-window-height", type=int, default=900, help="maximum displayed image height")
    parser.add_argument("--show-stderr", action="store_true", help="print raw_cast stderr logs")
    return parser.parse_args()


def start_stdout_raw_cast(args: argparse.Namespace, apk: Path) -> subprocess.Popen:
    log(f"[apk] using {apk.resolve()}")
    log(f"[adb] push {apk} -> {args.remote_apk}")
    run_adb(args, ["push", str(apk), args.remote_apk], "adb push")

    command = " ".join(
        [
            f"CLASSPATH={shlex.quote(args.remote_apk)}",
            "app_process",
            "/",
            "ink.mol.raw_cast.Main",
            "--mode=stdout",
            f"--format={shlex.quote(args.format)}",
            f"--fps={int(args.fps)}",
            f"--compress={shlex.quote(args.compress)}",
            "2>/dev/null",
        ]
    )
    log("[adb] start raw_cast stdout via exec-out")
    proc = subprocess.Popen(
        adb_cmd(args, ["exec-out", "sh", "-c", command]),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    if proc.stdout is None or proc.stderr is None:
        raise RuntimeError("failed to capture raw_cast stdout/stderr")
    BinaryStreamPump(proc.stderr, "raw_cast-stderr", echo=args.show_stderr)
    return proc


def read_exact_stream(stream, size: int, stop_event: Optional[threading.Event] = None) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        if stop_event is not None and stop_event.is_set():
            raise InterruptedError("stdout reader stopped")
        part = stream.read(size - len(chunks))
        if not part:
            raise EOFError("stdout closed while reading frame")
        chunks.extend(part)
    return bytes(chunks)


def stdout_reader_loop(args, proc, image_queue, error_queue, stop_event, bench_state: BenchState, Image, lz4_block) -> None:
    if proc.stdout is None:
        error_queue.put(RuntimeError("raw_cast stdout is not available"))
        stop_event.set()
        return

    metrics = Metrics(args.fps_window)
    max_payload_bytes = args.max_payload_mib * 1024 * 1024
    expect_lz4 = args.compress == "lz4"
    expected_format = FORMAT_IDS[args.format]
    bytes_per_pixel = FORMAT_BYTES_PER_PIXEL[args.format]
    bench_label = f"bench stdout {args.format}/{args.compress}"
    last_bench_update = 0.0
    try:
        version = parse_banner(read_exact_stream(proc.stdout, 8, stop_event))
        log(f"[stdout] protocol version={version}")
        bench_state.set_overlay(f"{bench_label}\nwaiting for frames")
        while not stop_event.is_set():
            header = parse_frame_header(
                read_exact_stream(proc.stdout, FRAME_HEADER_SIZE, stop_event),
                max_payload_bytes,
                expect_lz4,
                expected_format,
                args.format,
            )
            payload = read_exact_stream(proc.stdout, header.payload_size, stop_event)
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


def main() -> int:
    args = parse_args()
    args.window_title = f"raw_cast stdout {args.format}/{args.compress}"
    proc: Optional[subprocess.Popen] = None
    stop_event = threading.Event()
    image_queue: "queue.Queue[FrameItem]" = queue.Queue(maxsize=1)
    error_queue: "queue.Queue[BaseException]" = queue.Queue()
    bench_state = BenchState()
    reader: Optional[threading.Thread] = None
    try:
        log(f"[raw_cast-python] starting stdout {args.format}/{args.compress} viewer")
        log(f"[cwd] {Path.cwd()}")
        log("[prepare] checking adb")
        resolve_adb(args)
        select_adb_device(args)
        log("[prepare] checking APK")
        apk = prepare_apk()
        tk, Image, ImageTk, lz4_block = load_runtime_deps(args.compress)
        proc = start_stdout_raw_cast(args, apk)
        reader = threading.Thread(
            target=stdout_reader_loop,
            args=(args, proc, image_queue, error_queue, stop_event, bench_state, Image, lz4_block),
            name="stdout-reader",
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
        terminate_process(proc)


if __name__ == "__main__":
    raise SystemExit(main())
