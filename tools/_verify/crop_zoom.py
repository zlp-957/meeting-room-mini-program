# -*- coding: utf-8 -*-
"""裁剪截图的指定区域并做最近邻放大，用于逐像素复核。
用法: python crop_zoom.py <src.png> <out.png> x0 y0 x1 y1 [倍数]
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import icon_kit as K
from make_27px_nearest import decode_png


def main():
    src, out = sys.argv[1], sys.argv[2]
    x0, y0, x1, y1 = map(int, sys.argv[3:7])
    mag = int(sys.argv[7]) if len(sys.argv) > 7 else 3

    w, h, bpp, px = decode_png(src)
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w, x1), min(h, y1)
    cw, ch = x1 - x0, y1 - y0
    W, H = cw * mag, ch * mag
    canvas = bytearray(W * H * 4)
    for y in range(ch):
        for x in range(cw):
            si = ((y0 + y) * w + (x0 + x)) * bpp
            r, g, b = px[si], px[si + 1], px[si + 2]
            for dy in range(mag):
                base = ((y * mag + dy) * W + x * mag) * 4
                for dx in range(mag):
                    di = base + dx * 4
                    canvas[di] = r
                    canvas[di + 1] = g
                    canvas[di + 2] = b
                    canvas[di + 3] = 255
    K.write_png(out, W, H, canvas)
    print("crop %s -> %s  %dx%d  x%d" % ((x0, y0, x1, y1), out, W, H, mag))


if __name__ == "__main__":
    main()
