# -*- coding: utf-8 -*-
"""
预约页静态快照：把 create.wxss 的 rpx 折算成 px，套上真实数据生成 HTML，
再用 Edge 无头截图核对「月份 + 日期双滑动」和「双列滚轮时间选择器」。

picker-view 是原生组件，HTML 里还原不了滚动物理，所以用等价结构近似：
容器高 = 行高 × 5，中间一行铺淡蓝高亮带 + 上下白色渐隐遮罩。

用法：
    python tools/_verify/booking_preview.py           # 正常态（明天，有可选时段）
    python tools/_verify/booking_preview.py empty     # 空态（今天已过完营业时间）
输出：
    tools/_verify/booking-picker.html
    tools/_verify/booking-picker-empty.html
"""
import datetime
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MINI = os.path.join(BASE, 'miniprogram')

MODE = sys.argv[1] if len(sys.argv) > 1 else 'normal'
EMPTY = MODE == 'empty'

VIEWPORT_PT = 375
RPX = VIEWPORT_PT / 750.0

# ===== 与 create.js 保持一致的常量 =====
SLOT = 15
OPEN_MIN = 8 * 60
CLOSE_MIN = 22 * 60
GAP_MIN = 15
DATE_ITEM_W = 96
DATE_ITEM_GAP = 14
MAX_ADVANCE_DAYS = 14

# ===== 演示数据：今天 2026-10-29（周四），已占 09:00-10:00 / 14:00-15:30 =====
TODAY = (2026, 10, 29)
BOOKINGS = [(9 * 60, 10 * 60), (14 * 60, 15 * 60 + 30)]
# 空态用今天（营业时间已过完），正常态用明天
SELECTED_DATE = '2026-10-29' if EMPTY else '2026-10-30'
SELECTED_START = 11 * 60
SELECTED_END = 12 * 60

OUT = os.path.join(BASE, 'tools', '_verify',
                   'booking-picker%s.html' % ('-empty' if EMPTY else ''))


def pad(n):
    return '%02d' % n


def hm(m):
    return '%s:%s' % (pad(m // 60), pad(m % 60))


def rpx_to_px(css):
    def repl(m):
        v = float(m.group(1)) * RPX
        s = ('%.4f' % v).rstrip('0').rstrip('.')
        return (s if s else '0') + 'px'
    return re.sub(r'(-?\d+(?:\.\d+)?)rpx', repl, css)


def load_css():
    css = open(os.path.join(MINI, 'pages', 'booking', 'create.wxss'), encoding='utf-8').read()
    app = open(os.path.join(MINI, 'app.wxss'), encoding='utf-8').read()
    # 全局变量定义在 page 选择器里，换成 body 才能被继承
    app = app.replace('page {', 'body {', 1)
    return app + '\n' + css


def build_cells():
    cells = []
    m = OPEN_MIN
    while m < CLOSE_MIN:
        cells.append({'m': m, 'booked': False, 'buffer': False, 'past': False})
        m += SLOT

    for bs, be in BOOKINGS:
        si = (bs - OPEN_MIN) // SLOT
        ei = -(-(be - OPEN_MIN) // SLOT)
        for i in range(max(0, si), min(len(cells), ei)):
            cells[i]['booked'] = True

    for bs, be in BOOKINGS:
        for m in range(max(OPEN_MIN, bs - GAP_MIN), bs, SLOT):
            i = (m - OPEN_MIN) // SLOT
            if 0 <= i < len(cells) and not cells[i]['booked']:
                cells[i]['buffer'] = True
        for m in range(be, min(CLOSE_MIN, be + GAP_MIN), SLOT):
            i = (m - OPEN_MIN) // SLOT
            if 0 <= i < len(cells) and not cells[i]['booked']:
                cells[i]['buffer'] = True

    # 空态：模拟「今天的营业时间已经过完」，所有非占用段都算已过
    if EMPTY:
        for c in cells:
            if not c['booked'] and not c['buffer']:
                c['past'] = True
    return cells


def dis(c):
    return c['booked'] or c['buffer'] or c['past']


def build_start_options(cells):
    opts = []
    for i, c in enumerate(cells):
        if dis(c):
            continue
        if i + 1 >= len(cells) or dis(cells[i + 1]):
            continue
        opts.append(c['m'])
    return opts


def build_end_options(cells, start_min):
    idx = next((i for i, c in enumerate(cells) if c['m'] == start_min), -1)
    opts = []
    if idx < 0:
        return opts
    for i in range(idx + 1, len(cells)):
        if dis(cells[i]):
            break
        end_min = cells[i]['m'] + SLOT
        if end_min > CLOSE_MIN:
            break
        opts.append({'slot': cells[i]['m'], 'label': end_min})
    return opts


def build_avail_text(cells):
    ranges = []
    i = 0
    while i < len(cells):
        if dis(cells[i]):
            i += 1
            continue
        j = i
        while j + 1 < len(cells) and not dis(cells[j + 1]):
            j += 1
        ranges.append('%s-%s' % (hm(cells[i]['m']), hm(cells[j]['m'] + SLOT)))
        i = j + 1
    if not ranges:
        return '当日已无可预约时段'
    shown = '、'.join(ranges[:3])
    return shown + (' 等 %d 段' % len(ranges) if len(ranges) > 3 else '')


def build_dates():
    today = datetime.date(*TODAY)
    weeks = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
    out = []
    months = {}
    for i in range(MAX_ADVANCE_DAYS):
        d = today + datetime.timedelta(days=i)
        value = d.strftime('%Y-%m-%d')
        ym = value[:7]
        if ym not in months:
            months[ym] = {'ym': ym, 'label': '%d月' % d.month}
        out.append({
            'value': value,
            'day': d.day,
            'week': '今天' if i == 0 else weeks[d.weekday()],
            'ym': ym,
        })
    return out, list(months.values())


def main():
    cells = build_cells()
    starts = build_start_options(cells)
    avail_text = build_avail_text(cells)
    dates, months = build_dates()
    today_str = '-'.join('%02d' % x for x in (TODAY[0], TODAY[1], TODAY[2]))

    active_ym = SELECTED_DATE[:7]
    sel_idx = next((i for i, d in enumerate(dates) if d['value'] == SELECTED_DATE), 0)
    sel_label = ''
    for d in dates:
        if d['value'] == SELECTED_DATE:
            sel_label = '%d月%d日 %s' % (int(d['value'][5:7]), d['day'], d['week'])
            break

    # 选中日期居中
    item_w = DATE_ITEM_W * RPX
    step = (DATE_ITEM_W + DATE_ITEM_GAP) * RPX
    content_w = (VIEWPORT_PT - 2 * 32 * RPX) - 2 * 30 * RPX
    scroll_left = max(0.0, sel_idx * step - (content_w - item_w) / 2)

    # ---- 日期行 ----
    date_html = []
    for d in dates:
        cls = 'date-item'
        if d['value'] == SELECTED_DATE:
            cls += ' active'
        if d['value'] == today_str:
            cls += ' is-today'
        date_html.append(
            '<div class="%s"><span class="date-week">%s</span>'
            '<span class="date-num">%d</span></div>' % (cls, d['week'], d['day'])
        )

    month_html = []
    for m in months:
        cls = 'month-chip active' if m['ym'] == active_ym else 'month-chip'
        month_html.append('<div class="%s">%s</div>' % (cls, m['label']))

    # ---- 时间段：有候选就是滚轮，没有就是空态 ----
    if starts:
        end_opts = build_end_options(cells, SELECTED_START)
        si = next((i for i, m in enumerate(starts) if m == SELECTED_START), 0)
        ei = next((i for i, o in enumerate(end_opts) if o['slot'] == SELECTED_END - SLOT), 0)

        def col_html(items, idx, is_label):
            rows = []
            for k in range(idx - 2, idx + 3):
                if 0 <= k < len(items):
                    txt = hm(items[k]['label']) if is_label else hm(items[k])
                    rows.append('<div class="tp-item">%s</div>' % txt)
                else:
                    rows.append('<div class="tp-item tp-item-empty"></div>')
            return ''.join(rows)

        summary = '%s - %s' % (hm(SELECTED_START), hm(SELECTED_END))
        time_block = '''<div class="time-picker">
          <div class="tp-caption">
            <span class="tp-caption-text">开始</span>
            <span class="tp-caption-text">结束</span>
          </div>
          <div class="tp-view">
            <div class="tp-col">%s</div>
            <div class="tp-col">%s</div>
            <div class="tp-indicator"></div>
            <div class="tp-mask"></div>
            <div class="tp-mask-b"></div>
          </div>
        </div>''' % (col_html(starts, si, False), col_html(end_opts, ei, True))
        print('start options:', len(starts), 'selected:', hm(starts[si]))
        print('end options:', len(end_opts), 'selected:', hm(end_opts[ei]['label']))
    else:
        summary = '请选择'
        time_block = '''<div class="empty-slots">
          <span class="empty-slots-title">当日没有可预约时段</span>
          <span class="empty-slots-sub">换个日期看看，或联系管理员确认开放时间</span>
        </div>'''
        print('empty state: no start options')

    # 空态已经有说明文案了，再跟一行「可选 当日已无可预约时段」是重复的
    avail_block = ('''<div class="avail-tip">
          <span class="avail-label">可选</span>
          <span class="avail-text">%s</span>
        </div>''' % avail_text) if starts else ''

    css = rpx_to_px(load_css())
    preview_only = '''
html, body { margin: 0; padding: 0; }
body { background: #e9ecf2; display: block; padding: 20px 0; }
.phone { width: %dpx; margin: 0 auto; background: #f5f6f8; border-radius: 14px;
  overflow: hidden; box-shadow: 0 18px 50px rgba(15,23,42,.16); }
.booking-page { padding-bottom: 0 !important; min-height: auto !important; }
.bottom-btn { position: static !important; }
/* 日期行滚动效果：外层裁切，内层负偏移 */
.date-scroll { overflow: hidden; }
.date-row { margin-left: %spx; }
/* picker-view 原生布局在 HTML 里得自己搭 */
.tp-view { position: relative; display: flex; overflow: hidden; }
.tp-col { flex: 1; display: flex; flex-direction: column; position: relative; z-index: 2; }
.tp-item-empty { visibility: hidden; }
/* 淡蓝选中带压在文字底下 */
.tp-indicator { position: absolute; left: 0; right: 0; top: %spx;
  height: %spx; pointer-events: none; z-index: 1; }
.tp-mask { position: absolute; left: 0; right: 0; top: 0; height: %spx; pointer-events: none;
  background-image: linear-gradient(180deg, rgba(255,255,255,.98), rgba(255,255,255,0));
  z-index: 3; }
.tp-mask-b { position: absolute; left: 0; right: 0; bottom: 0; height: %spx; pointer-events: none;
  background-image: linear-gradient(0deg, rgba(255,255,255,.98), rgba(255,255,255,0));
  z-index: 3; }
''' % (
        VIEWPORT_PT,
        ('%.3f' % scroll_left).rstrip('0').rstrip('.'),
        2 * 72 * RPX, 72 * RPX, 72 * RPX, 72 * RPX,
    )

    html = '''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>预约页 · 双滑动日期 + 双列滚轮</title>
<style>
%s
%s
</style></head>
<body>
<div class="phone">
  <div class="booking-page">
    <div class="room-head">
      <span class="room-head-name">3号会议室 · 黄河</span>
      <span class="room-head-hint">营业时间 08:00 - 22:00</span>
    </div>

    <div class="card">
      <div class="section">
        <div class="section-head">
          <span class="section-label">预约日期</span>
          <span class="section-value">%s</span>
        </div>
        <div class="month-scroll"><div class="month-row">%s</div></div>
        <div class="date-scroll"><div class="date-row">%s</div></div>
      </div>

      <div class="section">
        <div class="section-head">
          <span class="section-label">时间段</span>
          <span class="section-value">%s</span>
        </div>
        %s
        %s
      </div>

      <div class="section">
        <span class="section-label">会议主题</span>
        <input class="subject-input" placeholder="请输入会议主题（必填）">
      </div>
    </div>

    <div class="bottom-btn"><div class="btn-primary">提交预约</div></div>
  </div>
</div>
</body></html>
''' % (
        css, preview_only,
        sel_label,
        ''.join(month_html), ''.join(date_html),
        summary, time_block, avail_block,
    )

    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(html)
    print('wrote', OUT)
    print('avail:', avail_text)


if __name__ == '__main__':
    main()
