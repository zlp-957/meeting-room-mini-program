# -*- coding: utf-8 -*-
"""把「我的」页按 375pt 折算成 HTML，用于截图核对菜单顺序。

只渲染已绑定手机号的状态（用户反馈基于这个状态）。
用法：python tools/_verify/mine_preview.py
"""
import io
import re

RPX = 375.0 / 750.0


def px(v):
    s = ('%.3f' % (float(v) * RPX)).rstrip('0').rstrip('.')
    return (s or '0') + 'px'


def rpx_to_px(css):
    return re.sub(r'(-?\d+(?:\.\d+)?)rpx', lambda m: px(float(m.group(1))), css)


def load_css():
    out = []
    for p in ('miniprogram/app.wxss', 'miniprogram/pages/mine/mine.wxss'):
        s = io.open(p, encoding='utf-8').read()
        s = rpx_to_px(s).replace('page {', 'body {').replace('page{', 'body{')
        out.append(s)
    return '\n'.join(out)


CELLS = [
    ('修改昵称', '<span class="cell-value">玖伍柒</span><span class="cell-arrow">\u203a</span>'),
    ('修改头像', '<span class="cell-avatar" style="background:#f0d9cf"></span>'
                 '<span class="cell-arrow">\u203a</span>'),
    ('修改手机号', '<span class="cell-arrow">\u203a</span>'),
    ('通知', '<span class="cell-badge">3</span><span class="cell-arrow">\u203a</span>'),
    ('公告', '<span class="cell-arrow">\u203a</span>'),
]

BODY = '''<div class="mine-page">
  <div class="profile">
    <div class="avatar-wrap">
      <div class="avatar">玖</div>
      <div class="avatar-edit"><span class="avatar-edit-icon">\uff0b</span></div>
    </div>
    <div class="profile-main">
      <div class="name-line"><span class="name">玖伍柒</span><span class="name-edit">\u270e</span></div>
      <div class="phone-line"><span class="phone">138****8888</span><span class="tag-ok">\u5df2\u7ed1\u5b9a</span></div>
    </div>
  </div>

  <div class="cell-group">
    %s
  </div>

  <div class="cell-group">
    <div class="cell"><span class="cell-label">\u9000\u51fa\u767b\u5f55</span><span class="cell-arrow">\u203a</span></div>
  </div>

  <div class="version">\u4f1a\u8bae\u5ba4\u9884\u7ea6 V1.0</div>
</div>'''


def main():
    cells = '\n    '.join(
        '<div class="cell"><span class="cell-label">%s</span>%s</div>' % (t, r)
        for t, r in CELLS
    )
    html = '''<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; animation: none !important; }
%s
body { width: 375px; background: #f5f7fa; }
.avatar { background: linear-gradient(135deg, #3b82f6, #6366f1); color: #fff;
          display: flex; align-items: center; justify-content: center; }
.cell-avatar { display: inline-block; }
</style></head><body>%s</body></html>''' % (load_css(), BODY % cells)
    io.open('tools/_verify/mine.html', 'w', encoding='utf-8').write(html)
    print('-> tools/_verify/mine.html', len(html))


if __name__ == '__main__':
    main()
