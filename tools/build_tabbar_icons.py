"""
tabBar 图标构建脚本：读取 icons_spec.py 的几何定义，导出 81x81 PNG + 预览页。

用法：
    python tools/build_tabbar_icons.py

图元（全部以 24x24 设计稿为单位）：
    ("poly", [(x,y), ...], 线宽, 是否闭合)     折线描边，圆头圆角
    ("cap",  x0,y0, x1,y1, 线宽)               单段胶囊
    ("arc",  cx,cy, r, 起始角, 结束角, 线宽)   圆弧描边（角度制，y 轴向下）
    ("ring", cx,cy, r, 线宽)                   整圆环
    ("dot",  cx,cy, r)                         实心圆
    ("disc", cx,cy, r, 挖空半径或 None)         实心圆（可挖空成甜甜圈）
    ("rrect", x0,y0, x1,y1, 圆角)              实心圆角矩形
    ("rrects", x0,y0, x1,y1, 圆角, 线宽)        圆角矩形描边
    ("sub",  图元A, 图元B)                     A 中挖去 B
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import icon_kit as K
from icons_spec import ICONS, COLORS

SIZE = 81
OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "miniprogram", "images", "tabbar",
)


def to_sdf(p):
    kind = p[0]
    if kind == "poly":
        pts, width, closed = p[1], p[2], p[3]
        return lambda x, y: K.sd_polyline(x, y, pts, width, closed)
    if kind == "cap":
        _, ax, ay, bx, by, width = p
        return lambda x, y: K.sd_segment_capsule(x, y, ax, ay, bx, by, width)
    if kind == "arc":
        _, cx, cy, r, a0, a1, width = p
        return lambda x, y: K.sd_arc(x, y, cx, cy, r, a0, a1, width)
    if kind == "ring":
        _, cx, cy, r, width = p
        return lambda x, y: K.sd_circle_ring(x, y, cx, cy, r, width)
    if kind == "dot":
        _, cx, cy, r = p
        return lambda x, y: K.sd_circle_fill(x, y, cx, cy, r)
    if kind == "disc":
        _, cx, cy, r, hole = p
        if hole is None:
            return lambda x, y: K.sd_circle_fill(x, y, cx, cy, r)
        return lambda x, y: K.sd_subtract(
            K.sd_circle_fill(x, y, cx, cy, r), K.sd_circle_fill(x, y, cx, cy, hole)
        )
    if kind == "rrect":
        _, x0, y0, x1, y1, radius = p
        return lambda x, y: K.sd_rounded_rect(x, y, x0, y0, x1, y1, radius)
    if kind == "rrects":
        _, x0, y0, x1, y1, radius, width = p
        return lambda x, y: K.sd_rounded_rect(x, y, x0, y0, x1, y1, radius, width)
    if kind == "sub":
        a = to_sdf(p[1])
        b = to_sdf(p[2])
        return lambda x, y: K.sd_subtract(a(x, y), b(x, y))
    raise ValueError("未知图元: %s" % (kind,))


def build_one(name, state):
    shapes = [to_sdf(p) for p in ICONS[name][state]]
    color = COLORS["active"] if state == "active" else COLORS["normal"]
    px, w, h = K.render_smooth(shapes, size=SIZE, samples=4, color=color)
    filename = "%s.png" % name if state == "normal" else "%s-active.png" % name
    path = os.path.join(OUT_DIR, filename)
    K.write_png(path, w, h, px)
    # 统计非透明像素边界，确认视觉尺寸一致
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            if px[(y * w + x) * 4 + 3] > 20:
                xs.append(x)
                ys.append(y)
    if xs:
        bbox = (min(xs), min(ys), max(xs), max(ys))
        print("  %-24s %2dx%-2d 图形尺寸 %dx%d" % (
            filename, w, h, bbox[2] - bbox[0] + 1, bbox[3] - bbox[1] + 1))
    else:
        print("  %-24s 空白！" % filename)
    return path


def build_preview(paths):
    """生成预览页：27px 实际尺寸 + 81px 放大稿 + 深色底校验。"""
    tiles = []
    for name in ICONS:
        normal = "../miniprogram/images/tabbar/%s.png" % name
        active = "../miniprogram/images/tabbar/%s-active.png" % name
        tiles.append("""
    <div class="row">
      <div class="label">{name}</div>
      <div class="cell"><img src="{n}" class="s27"><img src="{a}" class="s27"><span class="cap">27px 实际尺寸</span></div>
      <div class="cell"><img src="{n}" class="s54"><img src="{a}" class="s54"><span class="cap">54px</span></div>
      <div class="cell"><img src="{n}" class="s81"><img src="{a}" class="s81"><span class="cap">81px 原始</span></div>
    </div>""".format(name=name, n=normal, a=active))

    html = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>tabBar 图标预览</title>
<style>
  body {{ margin:0; padding:40px; background:#f5f6f8;
         font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif; color:#111827; }}
  h1 {{ font-size:18px; font-weight:600; margin:0 0 6px; }}
  p.sub {{ font-size:13px; color:#6b7280; margin:0 0 28px; }}
  .card {{ background:#fff; border-radius:16px; padding:8px 28px 24px;
          box-shadow:0 2px 12px rgba(17,24,39,.06); margin-bottom:24px; }}
  .row {{ display:flex; align-items:center; gap:0; padding:18px 0;
         border-bottom:1px solid #f2f4f7; }}
  .row:last-child {{ border-bottom:none; }}
  .label {{ width:110px; font-size:14px; color:#374151; font-weight:500; }}
  .cell {{ display:flex; align-items:center; gap:6px; margin-right:44px; }}
  .cell img {{ display:block; }}
  .s27 {{ width:27px; height:27px; }}
  .s54 {{ width:54px; height:54px; }}
  .s81 {{ width:81px; height:81px; }}
  .cap {{ font-size:11px; color:#9aa3b2; margin-left:6px; }}
  .bar {{ display:flex; align-items:center; gap:24px; background:#fff; border-radius:16px;
         padding:14px 28px; box-shadow:0 2px 12px rgba(17,24,39,.06); }}
  .bar .t {{ font-size:12px; color:#94a3b8; }}
  .bar .t.on {{ color:#2563eb; font-weight:600; }}
  .bar img {{ width:24px; height:24px; display:block; margin-bottom:2px; }}
  .bar .item {{ display:flex; flex-direction:column; align-items:center; gap:2px; }}
</style></head>
<body>
  <h1>tabBar 图标预览</h1>
  <p class="sub">左列为未选中态（#94a3b8），右列为选中态（#2563eb）。请重点看 27px 那一组，那是真机实际显示大小。</p>
  <div class="card">{tiles}</div>
  <h1 style="font-size:14px;margin-bottom:12px;">模拟 tabBar 效果</h1>
  <div class="bar">
    <div class="item"><img src="../miniprogram/images/tabbar/room-active.png"><span class="t on">会议室</span></div>
    <div class="item"><img src="../miniprogram/images/tabbar/booking.png"><span class="t">我的预约</span></div>
    <div class="item"><img src="../miniprogram/images/tabbar/mine.png"><span class="t">我的</span></div>
  </div>
</body></html>""".format(tiles="".join(tiles))

    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "tools", "icon-preview.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print("预览页: %s" % out)


def main():
    """
    必须显式指定要构建的图标 —— 不带参数时一个都不构建。

    原因：miniprogram/images/tabbar 下现在躺着的是用户验收过的原图标，
    这套脚本生成的是另一套画法。2026-10-28 就是因为「无脑跑一遍」把 booking / mine
    覆盖成了脚本版，被用户否决后靠 tools/_backup 才还原回来。
    原始 PNG 的副本留在 tools/_backup/，别再让它被误覆盖。
    """
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    if not args:
        print("用法：python tools/build_tabbar_icons.py room|booking|mine|all")
        print("不带参数不会写任何文件 —— 保护 images/tabbar 下用户验收过的图标。")
        print("当前几何定义：%s" % ' / '.join(ICONS))
        return

    names = list(ICONS) if args == ['all'] else args
    unknown = [n for n in names if n not in ICONS]
    if unknown:
        print("未知图标：%s（可选 %s）" % (', '.join(unknown), ' / '.join(ICONS)))
        return

    print("导出 tabBar 图标 → %s" % OUT_DIR)
    paths = []
    for name in names:
        for state in ("normal", "active"):
            paths.append(build_one(name, state))
    build_preview(paths)


if __name__ == "__main__":
    main()
