#!/usr/bin/env python3
"""Raw TCP RGBA viewer for raw_cast with LZ4 compression."""

from __future__ import annotations

import raw_tcp_rgb565_lz4_viewer as viewer


viewer.DEFAULT_FORMAT = "rgba"
viewer.DEFAULT_COMPRESS = "lz4"


if __name__ == "__main__":
    raise SystemExit(viewer.main())
