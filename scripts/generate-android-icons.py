#!/usr/bin/env python3
"""Generate crisp raster launcher PNG icons (ic_launcher & ic_launcher_round)
for all Android density buckets (mdpi through xxxhdpi) matching DastYab's
indigo-to-violet gradient + stylized 'D' + palm + gold padlock emblem.

Pure Python standard library (zero Pillow/ImageMagick dependency) so it runs
reproducibly in any CI or local environment.
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def encode_rgba_png(width: int, height: int, pixels: bytearray) -> bytes:
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0 (None)
        raw.extend(pixels[y * stride : (y + 1) * stride])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), level=9)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", idat)
        + _png_chunk(b"IEND", b"")
    )


def _clamp01(v: float) -> float:
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def _blend(dst: tuple[float, float, float, float], src: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    sr, sg, sb, sa = src
    dr, dg, db, da = dst
    out_a = sa + da * (1.0 - sa)
    if out_a <= 1e-6:
        return (0.0, 0.0, 0.0, 0.0)
    out_r = (sr * sa + dr * da * (1.0 - sa)) / out_a
    out_g = (sg * sa + dg * da * (1.0 - sa)) / out_a
    out_b = (sb * sa + db * da * (1.0 - sa)) / out_a
    return (out_r, out_g, out_b, out_a)


def _sd_rounded_box(px: float, py: float, cx: float, cy: float, hx: float, hy: float, r: float) -> float:
    qx = abs(px - cx) - hx + r
    qy = abs(py - cy) - hy + r
    ox = max(qx, 0.0)
    oy = max(qy, 0.0)
    return math.hypot(ox, oy) + min(max(qx, qy), 0.0) - r


def _sd_circle(px: float, py: float, cx: float, cy: float, r: float) -> float:
    return math.hypot(px - cx, py - cy) - r


def _sd_capsule(px: float, py: float, ax: float, ay: float, bx: float, by: float, r: float) -> float:
    pax, pay = px - ax, py - ay
    bax, bay = bx - ax, by - ay
    denom = bax * bax + bay * bay
    h = _clamp01((pax * bax + pay * bay) / denom) if denom > 0 else 0.0
    return math.hypot(pax - bax * h, pay - bay * h) - r


def _coverage(sdf: float, aa: float) -> float:
    return _clamp01(0.5 - sdf / aa)


def _sample_pixel(nx: float, ny: float, round_icon: bool, aa: float) -> tuple[int, int, int, int]:
    # Coordinate space is [0, 108] matching ic_launcher_dastyab.xml viewport
    x = nx * 108.0
    y = ny * 108.0

    # Outer mask
    if round_icon:
        bg_sdf = _sd_circle(x, y, 54.0, 54.0, 50.0)
    else:
        bg_sdf = _sd_rounded_box(x, y, 54.0, 54.0, 50.0, 50.0, 20.0)

    bg_cov = _coverage(bg_sdf, aa)
    if bg_cov <= 0.0:
        return (0, 0, 0, 0)

    # Diagonal indigo -> violet gradient (#312E81 -> #7C3AED)
    t = _clamp01((x + y) / 216.0)
    r = (49.0 + (124.0 - 49.0) * t) / 255.0
    g = (46.0 + (58.0 - 46.0) * t) / 255.0
    b = (129.0 + (237.0 - 129.0) * t) / 255.0
    color = (r, g, b, bg_cov)

    # Subtle inner gold rim accent (#D4AF37 at low alpha)
    rim_sdf = abs(bg_sdf + 2.0) - 0.8
    rim_cov = _coverage(rim_sdf, aa) * bg_cov * 0.35
    if rim_cov > 0.0:
        color = _blend(color, (212.0 / 255.0, 175.0 / 255.0, 55.0 / 255.0, rim_cov))

    # Stylized white 'D' ring + stem
    stem_sdf = _sd_rounded_box(x, y, 36.0, 54.0, 6.0, 24.0, 2.0)
    outer_arc = max(_sd_circle(x, y, 48.0, 54.0, 24.0), 42.0 - x)
    inner_hole = _sd_circle(x, y, 48.0, 54.0, 14.0)
    arc_sdf = max(outer_arc, -inner_hole)
    d_sdf = min(stem_sdf, arc_sdf)
    d_cov = _coverage(d_sdf, aa) * bg_cov
    if d_cov > 0.0:
        color = _blend(color, (1.0, 1.0, 1.0, d_cov))

    # Lavender palm/hand fingers (#C4B5FD) rising on the right
    f1 = _sd_capsule(x, y, 63.0, 44.0, 63.0, 62.0, 4.4)
    f2 = _sd_capsule(x, y, 73.0, 40.0, 73.0, 62.0, 4.4)
    f3 = _sd_capsule(x, y, 83.0, 36.0, 83.0, 60.0, 4.4)
    palm = _sd_rounded_box(x, y, 73.0, 65.0, 14.5, 11.0, 8.0)
    hand_sdf = min(f1, f2, f3, palm)
    hand_cov = _coverage(hand_sdf, aa) * bg_cov
    if hand_cov > 0.0:
        color = _blend(color, (196.0 / 255.0, 181.0 / 255.0, 253.0 / 255.0, hand_cov))

    # Gold padlock emblem top-right (#FDE68A)
    lock_body = _sd_rounded_box(x, y, 82.0, 25.0, 6.0, 4.5, 1.5)
    shackle_outer = _sd_circle(x, y, 82.0, 19.0, 5.2)
    shackle_inner = _sd_circle(x, y, 82.0, 19.0, 2.8)
    shackle = max(max(shackle_outer, -shackle_inner), y - 21.0)
    lock_sdf = min(lock_body, shackle)
    lock_cov = _coverage(lock_sdf, aa) * bg_cov
    if lock_cov > 0.0:
        color = _blend(color, (253.0 / 255.0, 230.0 / 255.0, 138.0 / 255.0, lock_cov))

    return (
        int(round(_clamp01(color[0]) * 255.0)),
        int(round(_clamp01(color[1]) * 255.0)),
        int(round(_clamp01(color[2]) * 255.0)),
        int(round(_clamp01(color[3]) * 255.0)),
    )


def render_icon(size: int, round_icon: bool) -> bytes:
    pixels = bytearray(size * size * 4)
    aa = 108.0 / float(size)
    idx = 0
    for py in range(size):
        ny = (py + 0.5) / size
        for px in range(size):
            nx = (px + 0.5) / size
            r, g, b, a = _sample_pixel(nx, ny, round_icon, aa)
            pixels[idx] = r
            pixels[idx + 1] = g
            pixels[idx + 2] = b
            pixels[idx + 3] = a
            idx += 4
    return encode_rgba_png(size, size, pixels)


def main() -> None:
    res_dir = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "res"
    for folder, size in DENSITIES.items():
        out_dir = res_dir / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "ic_launcher.png").write_bytes(render_icon(size, round_icon=False))
        (out_dir / "ic_launcher_round.png").write_bytes(render_icon(size, round_icon=True))
        print(f"Generated {folder}: {size}x{size} ic_launcher.png & ic_launcher_round.png")


if __name__ == "__main__":
    main()
