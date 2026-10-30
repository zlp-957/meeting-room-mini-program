# -*- coding: utf-8 -*-
"""把「我的预约」页按 375pt 折算成 HTML，用于核对状态 tab 的等分布局。

tab 里放 5 个状态（与 wxml 一致），active 选中「已通过」，即用户截图的状态。
用法：python tools/_verify/my_preview.py
"""
import io
import re

RPX = 375.0 / 750.0

TABS = ['待审批', '已通过', '已驳回', '已取消', '已违约']
ACTIVE = '已通过'


def px(v):
    s = ('%.3f' % (float(v) * RPX)).rstrip('0').rstrip('.')
    return (s or '0') + 'px'


def rpx_to_px(css):
    return re.sub(r'(-?\d+(?:\.\d+)?)rpx', lambda m: px(float(m.group(1))), css)


def load_css():
    out = []
    for p in ('miniprogram/app.wxss', 'miniprogram/pages/booking/my.wxss'):
        s = io.open(p, encoding='utf-8').read()
        s = rpx_to_px(s).replace('page {', 'body {').replace('page{', 'body{')
        out.append(s)
    return '\n'.join(out)


def tabs():
    return '\n      '.join(
        '<div class="tab-item%s">%s</div>' % (' active' if t == ACTIVE else '', t)
        for t in TABS
    )


CARD = '''<div class="booking-card">
      <div class="card-head">
        <span class="subject">产品周会</span>
        <span class="badge badge-approved">已通过</span>
      </div>
      <div class="room-line"><div class="room-dot"></div><span class="room-name">3号会议室 · 黄河</span></div>
      <div class="time-row"><span class="time-date">10月30日 周五</span><span class="time-range">10:00 - 12:00</span></div>
      <div class="checkin-tip">已签到 · 10:02</div>
      <div class="sign-hint">扫码签到时间 09:40 - 10:20，请在会议室门口扫码</div>
      <div class="card-foot">
        <div class="btn-ghost">修改预约</div>
        <div class="btn-ghost">取消预约</div>
        <div class="btn-primary-sm">扫码签到</div>
      </div>
    </div>'''


def main():
    body = '''<div class="my-booking-page">
  <div class="tab-bar">
    <div class="tab-inner">
      %s
    </div>
  </div>

  <div class="booking-list">
    %s
    %s
  </div>
</div>''' % (tabs(), CARD, CARD)

    html = '''<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
* , *::before, *::after { animation: none !important; }
%s
body { width: 375px; background: #f5f7fa; }
</style></head><body>%s</body></html>''' % (load_css(), body)
    io.open('tools/_verify/my.html', 'w', encoding='utf-8').write(html)
    print('-> tools/_verify/my.html', len(html))


if __name__ == '__main__':
    main()
