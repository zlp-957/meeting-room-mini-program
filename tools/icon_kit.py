"""
tabBar 图标生成器 —— 纯 Python，无第三方依赖。

原理：用有符号距离场（SDF）描述图形，直接按像素计算覆盖率做抗锯齿，
再用标准库 zlib 写出 RGBA PNG。不依赖 PIL / cairosvg / Inkscape。

坐标系：设计稿统一 24x24，导出时按 scale = 目标像素 / 24 放大。
"""

import math
import struct
import zlib

# ---------------------------------------------------------------- PNG 输出


def write_png(path, width, height, pixels):
    """pixels: bytearray，长度 width*height*4，RGBA 顺序，premultiply 已处理。"""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


# ---------------------------------------------------------------- SDF 图元
# 约定：返回值 <0 表示在形状内部，>0 在外部，单位均为设计稿单位（24 网格）


def _dist_point_segment(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    denom = vx * vx + vy * vy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / denom))
    dx, dy = wx - t * vx, wy - t * vy
    return math.hypot(dx, dy)


def sd_polyline(px, py, pts, width, closed=False):
    """折线描边（圆头圆角）：到折线的最短距离减去半线宽。"""
    half = width / 2.0
    best = 1e9
    n = len(pts)
    seg_count = n if closed else n - 1
    for i in range(seg_count):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        d = _dist_point_segment(px, py, ax, ay, bx, by)
        if d < best:
            best = d
    return best - half


def sd_circle_ring(px, py, cx, cy, r, width):
    """圆环描边。"""
    d = math.hypot(px - cx, py - cy)
    return abs(d - r) - width / 2.0


def sd_circle_fill(px, py, cx, cy, r):
    return math.hypot(px - cx, py - cy) - r


def sd_arc(px, py, cx, cy, r, a0, a1, width):
    """圆弧描边，角度用度，顺时针（屏幕坐标 y 向下）。"""
    half = width / 2.0
    dx, dy = px - cx, py - cy
    ang = math.degrees(math.atan2(dy, dx)) % 360.0
    a0 %= 360.0
    a1 %= 360.0
    if a0 <= a1:
        inside_angle = a0 <= ang <= a1
    else:  # 跨 0 度
        inside_angle = ang >= a0 or ang <= a1
    if inside_angle:
        return abs(math.hypot(dx, dy) - r) - half
    # 角度不在弧段内，退化为到两个端点的距离
    best = 1e9
    for a in (a0, a1):
        ex = cx + r * math.cos(math.radians(a))
        ey = cy + r * math.sin(math.radians(a))
        d = math.hypot(px - ex, py - ey)
        if d < best:
            best = d
    return best - half


def sd_rounded_rect(px, py, x0, y0, x1, y1, radius, stroke=None):
    """圆角矩形；stroke 为 None 时是实心，否则是描边。"""
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    hx, hy = (x1 - x0) / 2.0 - radius, (y1 - y0) / 2.0 - radius
    qx = abs(px - cx) - hx
    qy = abs(py - cy) - hy
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    inside = min(max(qx, qy), 0.0)
    d = outside + inside - radius
    if stroke is None:
        return d
    return abs(d) - stroke / 2.0


def sd_segment_capsule(px, py, ax, ay, bx, by, width):
    return _dist_point_segment(px, py, ax, ay, bx, by) - width / 2.0


def sd_ellipse_fill(px, py, cx, cy, rx, ry):
    # 近似：归一化半径，足够小尺寸使用
    nx = (px - cx) / rx
    ny = (py - cy) / ry
    return (math.hypot(nx, ny) - 1.0) * min(rx, ry)


def sd_union(*fields):
    return min(fields)


def sd_subtract(base, cutter):
    """从 base 里挖掉 cutter。"""
    return max(base, -cutter)


# ---------------------------------------------------------------- 渲染


def render(shapes, size=81, design=24.0, color=(37, 99, 235), supersample=3):
    """shapes: [(fn, )] 列表，fn(px, py) -> sdf。

    返回 (pixels, w, h)，RGBA。
    """
    scale = size / design
    ss = supersample
    pixels = bytearray(size * size * 4)
    r, g, b = color
    inv = 1.0 / (ss * ss)
    for y in range(size):
        for x in range(size):
            acc = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    # 子采样点在像素内的位置，换算回设计稿坐标
                    dx = (x + (sx + 0.5) / ss) / scale
                    dy = (y + (sy + 0.5) / ss) / scale
                    d = 1e9
                    for fn in shapes:
                        v = fn(dx, dy)
                        if v < d:
                            d = v
                    # 用像素在设计稿单位下的边长做柔和过渡
                    edge = 0.6 / scale / ss
                    cov = 0.5 - d / (2 * edge)
                    if cov < 0.0:
                        cov = 0.0
                    elif cov > 1.0:
                        cov = 1.0
                    acc += cov
            alpha = int(round(255 * acc * inv))
            if alpha == 0:
                continue
            i = (y * size + x) * 4
            pixels[i] = r
            pixels[i + 1] = g
            pixels[i + 2] = b
            pixels[i + 3] = alpha
    return pixels, size, size


def render_smooth(shapes, size=81, design=24.0, color=(37, 99, 235), samples=4):
    """更稳的抗锯齿：samples×samples 超采样后平均 alpha。"""
    scale = size / design
    pixels = bytearray(size * size * 4)
    r, g, b = color
    total = samples * samples
    for y in range(size):
        for x in range(size):
            hits = 0
            for sy in range(samples):
                for sx in range(samples):
                    dx = (x + (sx + 0.5) / samples) / scale
                    dy = (y + (sy + 0.5) / samples) / scale
                    d = 1e9
                    for fn in shapes:
                        v = fn(dx, dy)
                        if v < d:
                            d = v
                    if d <= 0:
                        hits += 1
            if hits == 0:
                continue
            alpha = int(round(255.0 * hits / total))
            i = (y * size + x) * 4
            pixels[i] = r
            pixels[i + 1] = g
            pixels[i + 2] = b
            pixels[i + 3] = alpha
    return pixels, size, size
