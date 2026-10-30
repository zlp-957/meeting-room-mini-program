# -*- coding: utf-8 -*-
"""对比「会议室」图标的几种简化程度，在 27px（真机 tabBar 实际尺寸）下哪个还能读。

结论写进回复里：两栋楼 + 窗在 27px 下必然糊，因为一格只有 1.1px。
用法：python tools/_verify/room_variants.py  然后截图 room-variants.html
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, TOOLS)

import icon_kit as K
from build_tabbar_icons import to_sdf

TMP = os.environ.get('TEMP') or TOOLS

LEFT = ("rrects", 3.5, 3.5, 12, 21, 1, 2)
RIGHT = ("rrects", 12, 8.5, 19, 21, 1, 2)
GROUND = ("cap", 2, 21, 21, 21, 2)


def win(x0, y, x1, w=1.3):
    return ("cap", x0, y, x1, y, w)


VARIANTS = {
    'A 无窗': [LEFT, RIGHT, GROUND],
    'B 1条0.9': [LEFT, RIGHT, GROUND, win(5.5, 10.5, 10, 0.9)],
    'C 2条0.9': [LEFT, RIGHT, GROUND, win(5.5, 8, 10, 0.9), win(5.5, 14, 10, 0.9)],
    'D 2条1.15': [LEFT, RIGHT, GROUND, win(5.5, 8, 10, 1.15), win(5.5, 14, 10, 1.15)],
}


def main():
    cells = []
    for name, shapes in VARIANTS.items():
        sdf = [to_sdf(p) for p in shapes]
        for size, tag in ((27, '27px 真机'), (81, '81px 资源')):
            px, w, h = K.render_smooth(sdf, size=size, samples=6, color=(37, 99, 235))
            path = os.path.join(TMP, 'variant_%s_%d.png' % (name.split()[0], size))
            K.write_png(path, w, h, px)
            cells.append((name, tag, path, size))

    rows = []
    for name in VARIANTS:
        imgs = ''.join(
            '<div class="cell"><img src="file:///%s" style="width:%dpx;height:%dpx">'
            '<span>%s</span></div>' % (p.replace('\\', '/'), s * 2, s * 2, tag)
            for n, tag, p, s in cells if n == name
        )
        rows.append('<div class="row"><div class="label">%s</div>%s</div>' % (name, imgs))

    html = '''<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body { margin:0; padding:24px; background:#f5f6f8; font-family:-apple-system,"PingFang SC",sans-serif; }
.row { display:flex; align-items:center; background:#fff; border-radius:12px; padding:14px 18px; margin-bottom:12px; }
.label { width:110px; font-size:13px; color:#374151; }
.cell { display:flex; align-items:center; gap:10px; margin-right:36px; }
.cell img { display:block; image-rendering:pixelated; }
.cell span { font-size:11px; color:#9aa3b2; }
</style></head><body>%s</body></html>''' % ''.join(rows)
    out = os.path.join(HERE, 'room-variants.html')
    io.open(out, 'w', encoding='utf-8').write(html)
    print('->', out)


if __name__ == '__main__':
    main()
