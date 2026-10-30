# -*- coding: utf-8 -*-
"""按 login.wxss 的实际几何参数复现 Logo（含渐变、高光、拱门描边、门把手），
用于在真机尺寸下目视验收。参数必须与 WXSS 一致，改样式后同步改这里。

WXSS -> 本脚本（1px = 2rpx）
    底板 132rpx=66px, 圆角 38rpx=19px, 渐变 135deg #4f8dfd -> #2563eb
    高光 顶部 52% 高度, rgba(255,255,255,.22) -> 0
    门洞 宽 43%, 高 51%, top 27%, 描边 5rpx=2.5px, 上方半径 50%/40%, 底部开口
    把手 right 37%, top 60%, 直径 7rpx=3.5px
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import icon_kit as K

D = 66.0  # 设计尺寸（px @1x）

# 几何常量（全部按 66 参考系）
R_TILE = 19.0
DOOR_W = D * 0.43
DOOR_H = D * 0.51
DOOR_TOP = D * 0.27
DOOR_X0 = D / 2 - DOOR_W / 2
DOOR_X1 = D / 2 + DOOR_W / 2
DOOR_BOTTOM = DOOR_TOP + DOOR_H
STROKE = 2.5
RX = DOOR_W * 0.5
RY = DOOR_H * 0.40
ARCH_CY = DOOR_TOP + RY
KNOB_CX = D * (1 - 0.37)
KNOB_CY = D * 0.60
KNOB_R = 1.75
HL_H = D * 0.52

C_A = (0x4F, 0x8D, 0xFD)
C_B = (0x25, 0x63, 0xEB)


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def inside_tile(x, y):
    # 圆角矩形
    cx, cy = D / 2, D / 2
    qx = abs(x - cx) - (cx - R_TILE)
    qy = abs(y - cy) - (cy - R_TILE)
    return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - R_TILE


def inside_door_stroke(x, y):
    """门洞描边：两条竖边 + 上半椭圆。"""
    half = STROKE / 2

    def seg(ax, ay, bx, by):
        vx, vy = bx - ax, by - ay
        wx, wy = x - ax, y - ay
        den = vx * vx + vy * vy
        t = 0 if den == 0 else max(0, min(1, (wx * vx + wy * vy) / den))
        return math.hypot(wx - t * vx, wy - t * vy)

    d1 = seg(DOOR_X0, ARCH_CY, DOOR_X0, DOOR_BOTTOM)
    d2 = seg(DOOR_X1, ARCH_CY, DOOR_X1, DOOR_BOTTOM)

    # 上半椭圆：y <= ARCH_CY 部分
    if y <= ARCH_CY + half:
        nx = (x - D / 2) / RX
        ny = (y - ARCH_CY) / RY
        r = math.hypot(nx, ny)
        d3 = abs(r - 1.0) * min(RX, RY)
    else:
        d3 = 1e9
    return min(d1, d2, d3) - half


def render(size, bg=None):
    scale = size / D
    ss = 4
    out = bytearray(size * size * 4)
    for py in range(size):
        for px in range(size):
            acc_r = acc_g = acc_b = acc_a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    x = (px + (sx + 0.5) / ss) / scale
                    y = (py + (sy + 0.5) / ss) / scale
                    if inside_tile(x, y) > 0:
                        continue
                    t = (x + y) / (2 * D)  # 135deg
                    r, g, b = lerp(C_A, C_B, t)
                    # 顶部高光
                    if y < HL_H:
                        a = 0.22 * (1 - y / HL_H)
                        r, g, b = lerp((r, g, b), (255, 255, 255), a)
                    # 白色门洞
                    if inside_door_stroke(x, y) <= 0 or math.hypot(x - KNOB_CX, y - KNOB_CY) <= KNOB_R:
                        r = g = b = 255.0
                    acc_r += r
                    acc_g += g
                    acc_b += b
                    acc_a += 1.0
            n = ss * ss
            if acc_a == 0:
                continue
            a = acc_a / n
            i = (py * size + px) * 4
            out[i] = int(acc_r / acc_a)
            out[i + 1] = int(acc_g / acc_a)
            out[i + 2] = int(acc_b / acc_a)
            out[i + 3] = int(a * 255)
    return out, size, size


def flatten(img, w, h, bg=(240, 245, 255)):
    o = bytearray(w * h * 4)
    for i in range(w * h):
        a = img[i * 4 + 3] / 255.0
        for k in range(3):
            o[i * 4 + k] = int(img[i * 4 + k] * a + bg[k] * (1 - a))
        o[i * 4 + 3] = 255
    return o


def main():
    from contact_sheet import blit

    sizes = [(132, 2), (66, 1), (44, 1)]
    tiles = []
    for s, _ in sizes:
        img, w, h = render(s)
        tiles.append((img, w, h, s))
        K.write_png(os.path.join(HERE, "logo-%d.png" % s), w, h, img)

    pad = 36
    W = sum(t[1] for t in tiles) + pad * (len(tiles) + 1)
    H = max(t[2] for t in tiles) + pad * 2
    cv = bytearray([255] * (W * H * 4))
    # 背景模拟登录页顶部渐变
    for y in range(H):
        t = y / H
        c = lerp((0xE3, 0xEC, 0xFF), (0xF5, 0xF6, 0xF8), t)
        for x in range(W):
            i = (y * W + x) * 4
            cv[i], cv[i + 1], cv[i + 2], cv[i + 3] = int(c[0]), int(c[1]), int(c[2]), 255
    x = pad
    for img, w, h, s in tiles:
        flat = flatten(img, w, h)
        blit(cv, W, H, flat, w, h, x, (H - h) // 2)
        x += w + pad
    K.write_png(os.path.join(HERE, "logo-check.png"), W, H, cv)
    print("验收图:", os.path.join(HERE, "logo-check.png"), W, "x", H)


if __name__ == "__main__":
    main()
