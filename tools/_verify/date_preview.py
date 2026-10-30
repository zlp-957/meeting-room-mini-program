# -*- coding: utf-8 -*-
"""把预约页「日期卡 + 起止时间 + 概览条 + 滚轮弹层」按 375pt 折算成 HTML，用于截图核对。

左：收起态；右：滚轮弹层打开态。

用法：
    python tools/_verify/date_preview.py
"""
import datetime
import io
import re

RPX = 375.0 / 750.0           # 1rpx 在 375pt 屏上的像素
MAX_ADVANCE_DAYS = 14
WEEK_CN = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
DAY = 24 * 60

TODAY = datetime.date(2026, 10, 29)
OPEN, CLOSE = 8 * 60, 22 * 60
BOOKED = [((13, 0), (14, 30)), ((16, 0), (17, 0)), ((20, 0), (22, 0))]
SEL = ('10:00', '12:00')
PICK_HOUR = '10'
PICK_MIN = '00'


def px(v):
    s = ('%.3f' % (float(v) * RPX)).rstrip('0').rstrip('.')
    return (s or '0') + 'px'


def rpx_to_px(css):
    return re.sub(r'(-?\d+(?:\.\d+)?)rpx', lambda m: px(float(m.group(1))), css)


def load_css():
    out = []
    for p in ('miniprogram/app.wxss', 'miniprogram/pages/booking/create.wxss'):
        s = io.open(p, encoding='utf-8').read()
        s = rpx_to_px(s).replace('page {', 'body {').replace('page{', 'body{')
        out.append(s)
    return '\n'.join(out)


def pct(mins):
    return '%.3f' % (mins / DAY * 100)


def hm(t):
    return int(t[0]) * 60 + int(t[1])


def date_cards(selected_idx=1):
    html = []
    for i in range(MAX_ADVANCE_DAYS):
        d = TODAY + datetime.timedelta(days=i)
        week = '今天' if i == 0 else WEEK_CN[(d.weekday() + 1) % 7]
        on = ' on' if i == selected_idx else ''
        html.append('<div class="date-card%s" id="d%d"><span class="dc-md">%02d.%02d</span>'
                    '<span class="dc-week">%s</span></div>'
                    % (on, i, d.month, d.day, week))
    return ''.join(html)


def overview():
    bands = []
    for (h0, m0), (h1, m1) in BOOKED:
        a, b = h0 * 60 + m0, h1 * 60 + m1
        bands.append('<div class="ov-occ" style="left:%s%%;width:%s%%"></div>'
                     % (pct(a), pct(b - a)))
    s = hm(SEL[0].split(':'))
    e = hm(SEL[1].split(':'))
    marks = []
    for h in range(1, 24):
        mins = h * 60
        cls = 'ov-mark' if OPEN <= mins <= CLOSE else 'ov-mark is-mute'
        marks.append('<div class="%s" style="left:%s%%"></div>' % (cls, pct(mins)))
    labels = []
    for h in range(0, 23, 2):
        labels.append('<span class="ov-label" style="left:%s%%">%d</span>' % (pct(h * 60), h))
    labels.append('<div class="ov-label is-tomorrow"><span>次</span><span>日</span></div>')

    return '''<div class="ov">
  <div class="ov-track">
    <div class="ov-biz" style="left:%s%%;width:%s%%"></div>
    %s
    <div class="ov-sel" style="left:%s%%;width:%s%%"></div>
    %s
  </div>
  <div class="ov-labels">%s</div>
  <div class="ov-legend">
    <div class="lg"><div class="lg-sw is-occ"></div><span class="lg-tx">已占</span></div>
    <div class="lg"><div class="lg-sw is-free"></div><span class="lg-tx">可预约</span></div>
    <div class="lg"><div class="lg-sw is-sel"></div><span class="lg-tx">已选</span></div>
    <div class="lg"><div class="lg-sw is-off"></div><span class="lg-tx is-dim">非营业</span></div>
  </div>
</div>''' % (pct(OPEN), pct(CLOSE - OPEN), ''.join(bands), pct(s), pct(e - s),
                ''.join(marks), ''.join(labels))


def time_row(focus=None):
    def box(which, val):
        on = ' on' if focus == which else ''
        return ('<div class="time-box%s"><span class="tb-time">%s</span>'
                '<div class="tb-caret"></div></div>' % (on, val))
    return ('<div class="time-row">%s<span class="time-to">到</span>%s</div>'
            % (box('start', SEL[0]), box('end', SEL[1])))


def columns(items, cur, rows=5):
    half = rows // 2
    idx = items.index(cur)
    out = []
    for i in range(idx - half, idx + half + 1):
        if 0 <= i < len(items):
            cls = 'pk-item on' if i == idx else 'pk-item'
            out.append('<div class="%s">%s</div>' % (cls, items[i]))
        else:
            out.append('<div class="pk-item"></div>')
    return ''.join(out)


def picker_sheet(open_):
    hours = ['%02d' % h for h in range(OPEN // 60, CLOSE // 60 + 1)]
    mins = ['00', '15', '30', '45']
    on = ' open' if open_ else ''
    mask = ' show' if open_ else ''
    return '''<div class="pk-mask%s"></div>
<div class="pk-sheet%s">
  <div class="pk-grab"></div>
  <div class="pk-head">
    <span class="pk-title">选择开始时间</span>
    <span class="pk-range">08:00 – 22:00</span>
  </div>
  <div class="pk-view">
    <div style="display:flex">
      <div style="flex:1">%s</div>
      <div style="flex:1">%s</div>
    </div>
    <div class="pk-indicator" style="position:absolute;left:0;right:0;top:%s;pointer-events:none"></div>
  </div>
  <div class="pk-foot">
    <div class="pk-cancel">取消</div>
    <div class="pk-ok">确定</div>
  </div>
</div>''' % (mask, on, columns(hours, PICK_HOUR), columns(mins, PICK_MIN), px(160))


def phone(title, with_sheet):
    body = '''<div class="booking-page" style="padding-bottom:0">
  <div class="room-head">
    <div class="room-head-name">3号会议室 · 黄河</div>
    <div class="room-head-hint">营业时间 08:00 - 22:00</div>
  </div>
  <div class="card">
    <div class="section">
      <div class="section-label">预约日期</div>
      <div class="date-scroll"><div class="date-row">%s</div></div>
    </div>
  </div>
  <div class="card">
    <div class="section">
      <div class="section-head">
        <span class="section-label">时间段</span>
        <span class="section-value">2 小时</span>
      </div>
      %s
      %s
    </div>
  </div>
  <div class="card">
    <div class="section">
      <div class="section-label">会议主题</div>
      <input class="subject-input" placeholder="请输入会议主题（必填）">
    </div>
  </div>
  <div class="bottom-btn"><div class="btn-primary">提交预约</div></div>
  %s
</div>''' % (date_cards(), time_row('start' if with_sheet else None), overview(),
             picker_sheet(with_sheet))
    return ('<div class="frame"><div class="frame-tip">%s</div>'
            '<div class="phone">%s</div></div>' % (title, body))


def main():
    html = '''<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
%s
html,body{margin:0;padding:0;background:#e9eef5;font-family:-apple-system,
"PingFang SC","Microsoft YaHei",sans-serif;}
.wrap{display:flex;gap:20px;padding:0;}
.frame{width:375px;}
.frame-tip{font-size:12px;color:#6b7280;padding:10px 4px 6px;}
.phone{position:relative;width:375px;height:680px;overflow:hidden;
  background:#f5f6f8;border-radius:14px;}
*{animation:none !important;}
.phone .pk-mask,.phone .pk-sheet{position:absolute;}
.phone .booking-page{min-height:0;}
.phone .date-scroll{overflow-x:auto;}
.phone .date-scroll::-webkit-scrollbar{display:none;}
.phone .pk-view{position:relative;}
</style></head><body>
<div class="wrap">
%s
%s
</div></body></html>''' % (load_css(), phone('收起态：日期卡 + 概览条', False),
                           phone('弹层：时分滚轮', True))
    out = 'tools/_verify/booking-date.html'
    io.open(out, 'w', encoding='utf-8').write(html)
    print('->', out, len(html))


if __name__ == '__main__':
    main()
