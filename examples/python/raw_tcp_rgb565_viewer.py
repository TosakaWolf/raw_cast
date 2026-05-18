#!/usr/bin/env python3
"""Raw TCP RGB565 viewer for raw_cast without LZ4 compression."""

from __future__ import annotations

import raw_tcp_rgb565_lz4_viewer as viewer


viewer.DEFAULT_COMPRESS = "none"


if __name__ == "__main__":
    raise SystemExit(viewer.main())
