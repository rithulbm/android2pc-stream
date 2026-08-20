#!/usr/bin/env python3
"""Generate the Local Camera Receiver multi-resolution ICO using only Python stdlib."""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

NAVY = (24, 35, 44, 255)
GREEN = (31, 181, 125, 255)
WHITE = (250, 251, 247, 255)
TRANSPARENT = (0, 0, 0, 0)


def rounded_rect_contains(x: float, y: float, left: float, top: float, right: float, bottom: float, radius: float) -> bool:
    if left + radius <= x <= right - radius or top + radius <= y <= bottom - radius:
        return left <= x <= right and top <= y <= bottom
    cx = left + radius if x < left + radius else right - radius
    cy = top + radius if y < top + radius else bottom - radius
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2


def triangle_contains(px: float, py: float, points: tuple[tuple[float, float], ...]) -> bool:
    (ax, ay), (bx, by), (cx, cy) = points
    d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
    d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
    d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
    has_negative = d1 < 0 or d2 < 0 or d3 < 0
    has_positive = d1 > 0 or d2 > 0 or d3 > 0
    return not (has_negative and has_positive)


def pixel(size: int, x: int, y: int) -> tuple[int, int, int, int]:
    scale = size / 256.0
    px = (x + 0.5) / scale
    py = (y + 0.5) / scale

    if not rounded_rect_contains(px, py, 0, 0, 255, 255, 56):
        return TRANSPARENT

    color = NAVY
    if rounded_rect_contains(px, py, 49, 82, 179, 174, 18):
        color = WHITE
    if triangle_contains(px, py, ((179, 100), (218, 77), (218, 179))) or triangle_contains(
        px, py, ((179, 100), (218, 179), (179, 156))
    ):
        color = GREEN
    if (px - 116) ** 2 + (py - 127) ** 2 <= 24 ** 2:
        color = NAVY
    if (px - 67) ** 2 + (py - 102) ** 2 <= 8 ** 2:
        color = GREEN
    return color


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)


def make_png(size: int) -> bytes:
    rows = bytearray()
    for y in range(size):
        rows.append(0)
        for x in range(size):
            rows.extend(pixel(size, x, y))
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + png_chunk(b"IEND", b"")


def make_ico() -> bytes:
    sizes = (16, 24, 32, 48, 64, 128, 256)
    images = [make_png(size) for size in sizes]
    header = struct.pack("<HHH", 0, 1, len(images))
    offset = len(header) + 16 * len(images)
    directory = bytearray()
    payload = bytearray()
    for size, image in zip(sizes, images):
        encoded_size = 0 if size == 256 else size
        directory.extend(struct.pack("<BBBBHHII", encoded_size, encoded_size, 0, 0, 1, 32, len(image), offset))
        payload.extend(image)
        offset += len(image)
    return header + bytes(directory) + bytes(payload)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: generate_icon.py OUTPUT.ico", file=sys.stderr)
        return 2
    output = Path(sys.argv[1])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(make_ico())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
