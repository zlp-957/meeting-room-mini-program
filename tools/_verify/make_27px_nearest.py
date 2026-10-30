# -*- coding: utf-8 -*-
"""生成 27px 最近邻放大图（每格 = 1 个真实像素，放大 8 倍），供复核小尺寸下笔画是否糊。

行：room / booking / mine；列：未选中 / 选中。
基于 miniprogram/images/tabbar/*.png（81px 资源）双线性降到 27px 后做最近邻放大，
与 contact_sheet.py 的降采样方式一致。
"""
import os
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, TOOLS)
import icon_kit as K

SRC = os.path.normpath(os.path.join(TOOLS, "..", "miniprogram", "images", "tabbar"))
OUT = os.path.join(HERE, "27px-nearest.png")

MAG = 8           # 像素放大倍数
CELL = 27 * MAG
GAP = 18
BORDER = (226, 232, 240)


def decode_png(path):
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
            for yy, wy in ((y0, 1 - fy), (y1, fy)):
                for xx, wx in ((x0, 1 - fx), (x1, fx)):
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
    return out


def main():
    rows = ["room", "booking", "mine"]
    W = GAP + (CELL + GAP) * 2
    H = GAP + (CELL + GAP) * 3
    canvas = bytearray([255] * (W * H * 4))

    for r, name in enumerate(rows):
        for c, state in enumerate(("", "-active")):
            w, h, bpp, px = decode_png(os.path.join(SRC, name + state + ".png"))
            small = resize_bilinear(w, h, bpp, px, 27, 27)
            ox = GAP + c * (CELL + GAP)
            oy = GAP + r * (CELL + GAP)
            # 单元格描边，便于分辨格子边界
            for y in range(CELL):
                for x in range(CELL):
                    di = ((oy + y) * W + (ox + x)) * 4
                    if y < 1 or x < 1:
                        canvas[di:di + 4] = bytes(BORDER + (255,))
            for y in range(27):
                for x in range(27):
                    si = (y * 27 + x) * 4
                    a = small[si + 3]
                    if a == 0:
                        continue
                    al = a / 255.0
                    for dy in range(MAG):
                        for dx in range(MAG):
                            di = ((oy + y * MAG + dy) * W + (ox + x * MAG + dx)) * 4
                            for k in range(3):
                                canvas[di + k] = int(small[si + k] * al + 255 * (1 - al))
                            canvas[di + 3] = 255

    K.write_png(OUT, W, H, canvas)
    print("27px 最近邻放大图:", OUT, "(%dx%d, 放大 %dx：1 像素 = %dx%d 方块, 行=room/booking/mine, 列=未选/选中)" % (W, H, MAG, MAG, MAG))


if __name__ == "__main__":
    main()
