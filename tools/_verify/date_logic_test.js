// 预约页「日期卡 + 概览条 + 时分滚轮」的离线验证。
// 固定「今天 = 2026-10-29」，检查概览条百分比、滚轮取值、区间校验。
const fs = require('fs')
const path = require('path')

const SRC = path.join(__dirname, '../../miniprogram/pages/booking/create.js')
const RealDate = Date

class FakeDate extends RealDate {
  constructor (...a) {
    if (!a.length) super(2026, 9, 29)
    else super(...a)
  }
  static now () { return new RealDate(2026, 9, 29).getTime() }
}
global.Date = FakeDate

let cfg = null
global.Page = (o) => { cfg = o }
let lastToast = ''
global.wx = {
  getWindowInfo: () => ({ windowWidth: 375 }),
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast (o) { lastToast = (o && o.title) || '' },
  showLoading () {},
  hideLoading () {},
  setNavigationBarTitle () {},
  navigateBack () {},
  switchTab () {},
  showModal () {},
  requestSubscribeMessage () {},
  createSelectorQuery: () => ({
    in () { return this },
    select () { return this },
    boundingClientRect (cb) { cb({ width: 340, left: 0 }); return this },
    exec () {}
  })
}

eval(fs.readFileSync(SRC, 'utf8').replace("const { api } = require('../../utils/api')", 'const api = {}'))

function makePage () {
  const p = Object.assign({}, cfg)
  p.data = JSON.parse(JSON.stringify(cfg.data))
  p.setData = function (d) { Object.assign(this.data, d) }
  p.data.roomId = 1
  p.data.openTime = '08:00'
  p.data.closeTime = '22:00'
  p.loadBooked = () => { p.bookedCalls = (p.bookedCalls || 0) + 1 }
  p.initDateStrip()
  p.initSlots()
  return p
}

let fails = 0
function eq (name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want)
  if (!ok) { fails++; console.log('  FAIL', name, 'got', JSON.stringify(got), 'want', JSON.stringify(want)) }
  else console.log('  ok  ', name, '=', JSON.stringify(got))
}
function near (name, got, want, tol) {
  const ok = Math.abs(Number(got) - want) <= tol
  if (!ok) { fails++; console.log('  FAIL', name, 'got', got, 'want~', want) }
  else console.log('  ok  ', name, '=', got)
}

// 把 12:00-13:00 标成已占用（含前后 15 分钟缓冲）→ 禁用 11:45~13:00
function occupy (p, from, to) {
  const cells = p.data.cells.map((c) => Object.assign({}, c, { booked: false, buffer: false, past: false }))
  const open = 480
  for (let i = (from - open) / 15; i < (to - open) / 15; i++) cells[i].booked = true
  cells[(from - open) / 15 - 1].buffer = true
  cells[(to - open) / 15].buffer = true
  p.setData({ cells })
  p.buildOverview()
  p.ensureSelection(p.data.selectedStart, p.data.selectedEnd)
}

console.log('\n[1] 日期卡：14 天，两行文案')
let p = makePage()
eq('卡片数', p.data.dateItems.length, 14)
eq('首张 md', p.data.dateItems[0].md, '10.29')
eq('首张 week', p.data.dateItems[0].week, '今天')
eq('第二张 md', p.data.dateItems[1].md, '10.30')
eq('第二张 week', p.data.dateItems[1].week, '周五')
eq('默认选中', p.data.selectedDate, '2026-10-29')
eq('滚入视野', p.data.dateIntoView, 'd0')
eq('最后一天', p.maxDate, '2026-11-11')
eq('点日期换选中', (p.onDateTap({ currentTarget: { dataset: { date: '2026-11-02' } } }), p.data.selectedDate), '2026-11-02')
eq('换日期重拉占用', p.bookedCalls >= 1, true)
eq('滚到第 5 张', p.data.dateIntoView, 'd4')

console.log('\n[2] 概览条：全天 24 小时铺底')
p = makePage()
eq('槽位数', p.data.cells.length, 56)
near('营业段 left%', p.data.bizLeft, 33.333, 0.01)
near('营业段 width%', p.data.bizWidth, 58.333, 0.01)
eq('刻度线 23 条（1:00~23:00 每小时一条）', p.data.ovMarks.length, 23)
near('首条线在 4.167%（1:00）', p.data.ovMarks[0].left, 4.167, 0.001)
near('末条线在 95.833%（23:00）', p.data.ovMarks[22].left, 95.833, 0.001)
near('小时间隔 4.167%', Number(p.data.ovMarks[1].left) - Number(p.data.ovMarks[0].left), 4.167, 0.001)
eq('非营业段（4:00）用灰线', p.data.ovMarks[3].tone, 'mute')
eq('营业段（9:00）用白线', p.data.ovMarks[8].tone, 'light')
eq('营业段（22:00）用白线', p.data.ovMarks[21].tone, 'light')
eq('数字标签 12 个', p.data.ovLabels.length, 12)
eq(
  '数字标签为 0/2/…/22（两小时一个）',
  p.data.ovLabels.map((l) => l.text).join(','),
  '0,2,4,6,8,10,12,14,16,18,20,22'
)
near('首标签在 0%', p.data.ovLabels[0].left, 0, 0.001)
near('末标签在 91.667%', p.data.ovLabels[11].left, 91.667, 0.001)
eq('无占用时无段', p.data.ovBands.length, 0)

console.log('\n[3] 占用段位置：12:00-13:00 + 前后缓冲')
occupy(p, 720, 780)
eq('合并成 1 段', p.data.ovBands.length, 1)
near('段 left%（11:45）', p.data.ovBands[0].left, 48.958, 0.01)
near('段 width%（11:45-13:00 共 90 分钟）', p.data.ovBands[0].width, 6.25, 0.01)
eq('仍有可约时段', p.data.hasSlots, true)
eq('默认选区避开占用', p.data.selectedStart, '08:00')
eq('默认时长 1 小时', p.data.selectedEnd, '09:00')

console.log('\n[4] 滚轮：时列 08-22，分列 00/15/30/45')
p.openTimePicker({ currentTarget: { dataset: { which: 'start' } } })
eq('弹层打开', p.data.pickerOpen, true)
eq('标题', p.data.pickerTitle, '选择开始时间')
eq('时列首尾', [p.data.hourOptions[0], p.data.hourOptions[p.data.hourOptions.length - 1]], ['08', '22'])
eq('时列长度', p.data.hourOptions.length, 15)
eq('分列', p.data.minuteOptions, ['00', '15', '30', '45'])
eq('初始落在当前值（08:00）', p.data.pickerValue, [0, 0])
p.onPickerChange({ detail: { value: [14, 0] } })
eq('滚到 22 点只剩 00', p.data.minuteOptions, ['00'])
p.onPickerChange({ detail: { value: [2, 0] } })
eq('滚回 10 点分列恢复', p.data.minuteOptions, ['00', '15', '30', '45'])
p.onPickerChange({ detail: { value: [2, 3] } })
eq('分列同宽时保留分钟索引', p.data.pickerValue, [2, 3])

console.log('\n[5] 确认：非法值拦下，合法值落地')
p.setData({ pickerValue: [4, 2] })   // 12:30 落在占用里
p.confirmTimePicker()
eq('开始落在占用格里 → 提示', lastToast, '该时段已被占用')
eq('弹层不关', p.data.pickerOpen, true)
eq('选区没被改', p.data.selectedStart, '08:00')

p.openTimePicker({ currentTarget: { dataset: { which: 'end' } } })
p.setData({ pickerValue: [0, 0] })   // 08:00 不晚于开始
p.confirmTimePicker()
eq('结束早于开始 → 提示', lastToast, '结束时间要晚于开始时间')
eq('结束仍不生效', p.data.selectedEnd, '09:00')

p.setData({ pickerValue: [5, 0] })   // 13:00，区间跨过占用
p.confirmTimePicker()
eq('结束跨过占用 → 提示', lastToast, '这段时间已被占用')

p.setData({ pickerValue: [2, 0] })   // 10:00 合法
p.confirmTimePicker()
eq('落到 10:00', p.data.selectedEnd, '10:00')
eq('弹层关闭', p.data.pickerOpen, false)
eq('时长重算', p.data.durationText, '2 小时')
near('概览已选 left%', p.data.ovSelLeft, 33.333, 0.01)
near('概览已选 width%', p.data.ovSelWidth, 8.333, 0.01)

console.log('\n[6] 改开始时间：结束被顶到前面时自动顺延')
p.openTimePicker({ currentTarget: { dataset: { which: 'start' } } })
p.setData({ pickerValue: [2, 1] })   // 10:15，晚于当前结束 10:00
p.confirmTimePicker()
eq('开始改为 10:15', p.data.selectedStart, '10:15')
eq('结束顺延，不出空档', p.data.selectedEnd, '11:15')
eq('顺延后仍合法', p.isRangeFree(p.data.startIdx, p.data.endIdx), true)

console.log('\n[7] 全天占满：没有可约时段')
const p3 = makePage()
const full = p3.data.cells.map((c) => Object.assign({}, c, { booked: true }))
p3.setData({ cells: full })
p3.buildOverview()
p3.ensureSelection('', '')
eq('无可约', p3.data.hasSlots, false)
eq('清空选区', [p3.data.selectedStart, p3.data.selectedEnd], ['', ''])
eq('不可提交', p3.data.canSubmit, false)
eq('概览已选宽度归零', p3.data.ovSelWidth, 0)

console.log('\n' + (fails ? '✗ ' + fails + ' 项失败' : '✓ 全部通过'))
process.exit(fails ? 1 : 0)
