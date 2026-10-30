# -*- coding: utf-8 -*-
"""把 6 张图标拼成对照图，检查 27 / 54 / 81px 三档观感。"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import icon_kit as K

SRC = os.path.normpath(os.path.join(HERE, "..", "..", "miniprogram", "images", "tabbar"))
OUT = os.path.join(HERE, "contact-sheet.png")
NAMES = ["room", "booking", "mine"]


def decode_png(path):
    import zlib
    d = open(path, "rb").read()
    pos, w, h, ct, idat = 8, 0, 0, 0, b""
    while pos < len(d):
        ln = int.from_bytes(d[pos:pos + 4], "big")
        tag = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if tag == b"IHDR":
            w = int.from_bytes(data[0:4], "big")
            h = int.from_bytes(data[4:8], "big")
            ct = data[9]
        elif tag == b"IDAT":
            idat += data
        elif tag == b"IEND":
            break
    raw = zlib.decompress(idat)
    bpp = {6: 4, 2: 3}[ct]
    stride = w * bpp
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line
        prev = line
    return w, h, bpp, bytes(out)


def resize_bilinear(w, h, bpp, px, nw, nh):
    """缩放到目标尺寸（预乘 alpha 后插值，避免边缘发黑）。"""
    out = bytearray(nw * nh * 4)
    for ty in range(nh):
        sy = (ty + 0.5) * h / nh - 0.5
        y0 = max(0, int(sy))
        y1 = min(h - 1, y0 + 1)
        fy = sy - y0
        for tx in range(nw):
            sx = (tx + 0.5) * w / nw - 0.5
            x0 = max(0, int(sx))
            x1 = min(w - 1, x0 + 1)
            fx = sx - x0
            acc = [0.0, 0.0, 0.0, 0.0]
            for (yy, wy) in ((y0, 1 - fy), (y1, fy)):
                for (xx, wx) in ((x0, 1 - fx), (x1, fx)):
                    i = (yy * w + xx) * bpp
                    a = px[i + 3] / 255.0 if bpp == 4 else 1.0
                    wt = wx * wy
                    acc[0] += px[i] * a * wt
                    acc[1] += px[i + 1] * a * wt
                    acc[2] += px[i + 2] * a * wt
                    acc[3] += a * wt
            o = (ty * nw + tx) * 4
            if acc[3] > 0.0001:
                out[o] = int(acc[0] / acc[3])
                out[o + 1] = int(acc[1] / acc[3])
                out[o + 2] = int(acc[2] / acc[3])
                out[o + 3] = int(min(255, acc[3] * 255))
    return out, nw, nh


def blit(canvas, cw, ch, img, iw, ih, ox, oy):
    for y in range(ih):
        cy = oy + y
        if cy < 0 or cy >= ch:
            continue
        for x in range(iw):
            cx = ox + x
            if cx < 0 or cx >= cw:
                continue
            si = (y * iw + x) * 4
            a = img[si + 3] / 255.0
            if a <= 0:
                continue
            di = (cy * cw + cx) * 4
            for k in range(3):
                canvas[di + k] = int(img[si + k] * a + canvas[di + k] * (1 - a))
            canvas[di + 3] = 255


def main():
    W, H = 660, 330
    canvas = bytearray([255] * (W * H * 4))
    for i in range(W * H):
        canvas[i * 4 + 3] = 255

    cache = {}
    for name in NAMES:
        for state in ("", "-active"):
            p = os.path.join(SRC, name + state + ".png")
            cache[name + state] = decode_png(p)

    # 行：3 个图标；列：27px 双态 / 54px 双态 / 81px 双态
    row_y = [40, 145, 250]
    for r, name in enumerate(NAMES):
        y = row_y[r]
        x = 50
        for size in (27, 54, 81):
            for state in ("", "-active"):
                w, h, bpp, px = cache[name + state]
                key = (name + state, size)
                if size == 81:
                    img, iw, ih = resize_bilinear(w, h, bpp, px, 81, 81)
                else:
                    img, iw, ih = resize_bilinear(w, h, bpp, px, size, size)
                oy = y + (81 - size) // 2
                blit(canvas, W, H, img, iw, ih, x, oy)
                x += size + 14
            x += 22

    K.write_png(OUT, W, H, canvas)
    print("对照图:", OUT)


if __name__ == "__main__":
    main()
