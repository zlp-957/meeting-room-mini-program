const { api } = require('../../utils/api')

const SLOT_MINUTES = 15
// 可预约日期范围（与后端 meeting.max-advance-days 保持一致）
const MAX_ADVANCE_DAYS = 14
// 同会议室相邻两场预约之间的最小间隔（与后端 meeting.gap-minutes 保持一致）
const GAP_MINUTES = 15
const WEEK_CN = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
// 滚轮分钟列：业务粒度就是 15 分钟，只给这四个值，避免滚出 10:07 这种排不进去的时间
const MINUTE_OPTIONS = ['00', '15', '30', '45']
// 概览条按整天 24 小时铺开，跟参考图一致
const DAY_MINUTES = 24 * 60

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function formatDate(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** "08:30" / "08:30:00" -> 510 */
function toMinutes(t) {
  if (!t) return 0
  const parts = String(t).split(':')
  return Number(parts[0]) * 60 + Number(parts[1] || 0)
}

/** 510 -> "08:30" */
function formatHM(minutes) {
  return pad(Math.floor(minutes / 60)) + ':' + pad(minutes % 60)
}

Page({
  data: {
    roomId: null,
    roomName: '',
    openTime: '08:00',
    closeTime: '22:00',
    // ===== 日期（横排日期卡，一屏 5 个，可横滑） =====
    dateItems: [],
    todayStr: '',
    dateIntoView: '',
    // ===== 选择结果 =====
    selectedDate: '',
    // ===== 时间段 =====
    cells: [],
    // 选中的槽位区间 [startIdx, endIdx)，endIdx 为开区间
    startIdx: -1,
    endIdx: -1,
    selectedStart: '',
    selectedEnd: '',
    durationText: '',
    hasSlots: false,
    canSubmit: false,
    // 概览条：全天 24 小时，四档颜色（非营业 / 可约 / 已占 / 已选）
    bizLeft: 0,
    bizWidth: 0,
    ovBands: [],
    ovMarks: [],
    ovLabels: [],
    ovSelLeft: 0,
    ovSelWidth: 0,
    // 时分滚轮弹层
    pickerOpen: false,
    pickerWhich: 'start',
    pickerTitle: '选择开始时间',
    pickerValue: [0, 0],
    hourOptions: [],
    minuteOptions: MINUTE_OPTIONS,
    subject: '',
    // 编辑模式：带上预约 id 进来即为「修改预约」，与新建共用同一套滚轮逻辑
    isEdit: false,
    editId: null,
    rooms: [],
    roomIndex: 0,
    // 是否取不到会议室详情（网络/后端地址错误）。与「当天确实没空」区分，空态文案不同
    loadFailed: false,
    // 订阅消息模板 ID：由后端 /api/subscribe/templates 下发（后端配置为空则为空数组）
    subscribeTemplates: []
  },

  onLoad(options) {
    // 关键：把初始化包在 try/catch 里。
    // 之前 onLoad 是一路顺序执行，任何一步（哪怕是订阅模板这种可选能力）抛错，
    // 后面的 initDateStrip / loadRoom 都不会执行，页面就是「日期空、时段空、只剩默认营业时间」的一片空白，
    // 而且没有任何提示，非常难排查。现在即使出错也要保证本地渲染完成，并把错误显示出来。
    try {
      this.bootstrap(options)
    } catch (e) {
      console.error('[booking/create] 初始化失败', e)
      try {
        this.initDateStrip()
      } catch (e2) {
        console.error('[booking/create] 日期条初始化也失败', e2)
      }
      wx.showToast({
        title: '页面初始化异常：' + ((e && e.message) || e),
        icon: 'none',
        duration: 4000
      })
    }
  },

  bootstrap(options) {
    if (options.id) {
      // 编辑模式：房间名 / 主题 / 时间都要从后端取，列表页只传 id
      this.setData({ isEdit: true, editId: options.id })
      wx.setNavigationBarTitle({ title: '修改预约' })
      this.initDateStrip()
      this.safeLoadSubscribeTemplates()
      this.loadDetail(options.id)
      return
    }

    this.setData({
      roomId: options.roomId,
      roomName: options.roomName || ''
    })
    this.initDateStrip()
    this.safeLoadSubscribeTemplates()
    this.loadRoom()
  },

  /**
   * 拉取订阅模板 ID（可选能力）。
   *
   * 外层再包一层 try/catch：订阅消息只是增强触达，无论出什么问题都不能影响预约本身。
   */
  safeLoadSubscribeTemplates() {
    try {
      this.loadSubscribeTemplates()
    } catch (e) {
      console.warn('[subscribe] 模板 ID 拉取失败，忽略', e)
    }
  },

  /**
   * 编辑模式：拉详情并预填。
   *
   * 预填顺序很关键 —— 先把原时段写进 selectedStart / selectedEnd，
   * 等 loadRoom → initSlots → loadBooked 之后，由 ensureSelection(preferStart, preferEnd)
   * 把把手摆回原时段。顺序颠倒的话那时 cells 还没标好占用，校验会失败退回默认位置。
   */
  loadDetail(id) {
    wx.showLoading({ title: '加载中', mask: true })
    api.bookingDetail(id).then((b) => {
      wx.hideLoading()
      if (!b) {
        wx.showToast({ title: '预约不存在', icon: 'none' })
        setTimeout(() => wx.navigateBack(), 800)
        return
      }
      // selectedEnd 直接存「真实结束时刻」，提交时原样用，不再换算成槽位
      const bookDate = (b.startTime || '').substring(0, 10)
      this.setData({
        roomId: b.roomId,
        roomName: b.roomName || '',
        subject: b.subject || '',
        selectedStart: (b.startTime || '').substring(11, 16),
        selectedEnd: (b.endTime || '').substring(11, 16)
      })
      // 日期要切到原预约那天，否则改 10 天后的预约时看不到自己选的那天
      const di = (this.dateList || []).indexOf(bookDate)
      if (di >= 0) {
        this.setData({ selectedDate: bookDate })
        this.scrollDateIntoView(bookDate)
      }
      this.loadRooms()
      this.loadRoom()
    }).catch((err) => {
      wx.hideLoading()
      wx.showToast({ title: err.message, icon: 'none' })
      setTimeout(() => wx.navigateBack(), 800)
    })
  },

  /** 编辑模式才需要房间列表：允许把预约换到别的会议室 */
  loadRooms() {
    api.roomList().then((list) => {
      const rooms = (list || []).filter((r) => r.status === 1)
      const idx = rooms.findIndex((r) => r.id === this.data.roomId)
      this.setData({
        rooms: rooms.map((r) => ({ id: r.id, name: r.name })),
        roomIndex: idx >= 0 ? idx : 0
      })
    }).catch(() => {})
  },

  /** 换会议室：房间变了，时间轴的营业时间和占用情况都要重算，已选时段清空 */
  onRoomChange(e) {
    const idx = Number(e.detail.value)
    const room = this.data.rooms[idx]
    if (!room || room.id === this.data.roomId) return
    this.setData({
      roomIndex: idx,
      roomId: room.id,
      roomName: room.name,
      selectedStart: '',
      selectedEnd: '',
      canSubmit: false
    })
    this.loadRoom()
  },

  /**
   * 日期初始化：生成 [今天, 今天 + MAX_ADVANCE_DAYS - 1] 共 14 张日期卡。
   * 一屏放 5 张（宽 127rpx + 间距 12rpx），多出来的横滑看。
   * 日期统一用 'YYYY-MM-DD' 字符串直接比大小 —— 同格式下字典序就是时间序。
   */
  initDateStrip() {
    const now = new Date()
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    this.minDate = formatDate(today)
    this.todayStr = this.minDate

    const items = []
    for (let i = 0; i < MAX_ADVANCE_DAYS; i++) {
      const d = new Date(today)
      d.setDate(d.getDate() + i)
      const value = formatDate(d)
      items.push({
        value: value,
        // 10.29 —— 图上就是「月.日」两段数字，比「10月29日」窄，一行能多看几天
        md: pad(d.getMonth() + 1) + '.' + pad(d.getDate()),
        week: i === 0 ? '今天' : WEEK_CN[d.getDay()],
        today: i === 0
      })
    }
    this.dateList = items.map((it) => it.value)
    this.maxDate = this.dateList[this.dateList.length - 1]

    const first = items[0].value
    this.setData({
      dateItems: items,
      todayStr: this.todayStr,
      selectedDate: first,
      dateIntoView: 'd0'
    })
  },

  /** 选中某一天后把卡片滚进可视区（编辑模式回填时可能在第 10 张之外） */
  scrollDateIntoView(date) {
    const idx = (this.dateList || []).indexOf(date)
    if (idx >= 0) this.setData({ dateIntoView: 'd' + idx })
  },

  /** 点日期卡：换日期后原时段可能不可用，先清空再重拉占用 */
  onDateTap(e) {
    const date = e.currentTarget.dataset.date
    if (!date || date === this.data.selectedDate) return
    this.applyDate(date)
  },

  /**
   * 落地一次日期切换。
   * loadBooked 里的 ensureSelection 会把「仍然合法的原选择」恢复回来，
   * 所以大部分时候换日期不用重新选时间。
   */
  applyDate(date) {
    this.setData({
      selectedDate: date,
      selectedStart: '',
      selectedEnd: '',
      canSubmit: false
    })
    this.scrollDateIntoView(date)
    this.loadBooked()
  },

  /**
   * P1-11：读取会议室自己的营业时间，按时段粒度生成可选时间，
   * 不再对全部房间写死 08:00–20:00。
   */
  loadRoom() {
    api.roomDetail(this.data.roomId).then((room) => {
      const open = (room.openTime || '08:00:00').substring(0, 5)
      const close = (room.closeTime || '22:00:00').substring(0, 5)
      this.setData({
        roomName: room.name || this.data.roomName,
        openTime: open,
        closeTime: close,
        loadFailed: false
      })
      this.initSlots()
      this.loadBooked()
    }).catch((err) => {
      // 区分「取不到房间」与「当天确实没空」：
      // 原来两种情况都显示「当日没有可预约时段」，用户会去找管理员，其实是他手机连不上后端。
      this.setData({ loadFailed: true })
      wx.showToast({ title: err.message, icon: 'none' })
      this.initSlots()
      this.loadBooked()
    })
  },

  /**
   * 生成 15 分钟粒度的「可用性模型」。
   *
   * cells 是纯数据：索引 i = 距营业开始第几个 15 分钟，占用/缓冲/已过都在上面打标记。
   * 概览条和滚轮校验都只读它，不再有第二套时间算术。
   */
  initSlots() {
    const start = toMinutes(this.data.openTime)
    const end = toMinutes(this.data.closeTime)
    const cells = []
    for (let m = start, i = 0; m < end; m += SLOT_MINUTES, i++) {
      cells.push({
        idx: i,
        time: formatHM(m),
        booked: false,
        buffer: false,
        past: false
      })
    }
    this.setData({ cells: cells })
    this.buildOverview()
    this.ensureSelection(this.data.selectedStart, this.data.selectedEnd)
  },

  isCellDisabled(cell) {
    return !!(cell && (cell.booked || cell.buffer || cell.past))
  },

  /** [s, e) 整段是否都可约；e 允许等于 cells.length（约到打烊） */
  isRangeFree(s, e) {
    const cells = this.data.cells || []
    if (s < 0 || e <= s || e > cells.length) return false
    for (let i = s; i < e; i++) {
      if (this.isCellDisabled(cells[i])) return false
    }
    return true
  },

  /**
   * 重建概览条：营业段位置 + 已占段 + 刻度。
   *
   * 整条按 24 小时铺（参考图就是全天的），营业时间之外靠底色区分、不画段。
   * 用百分比而不是 px，屏幕多宽都对得上。
   */
  buildOverview() {
    const cells = this.data.cells || []
    const open = toMinutes(this.data.openTime)
    const close = toMinutes(this.data.closeTime)
    const pct = (mins) => (mins / DAY_MINUTES * 100).toFixed(3)

    // 已占段：连续不可用的格子压成一段，避免一格一个节点
    const bands = []
    let i = 0
    while (i < cells.length) {
      if (!this.isCellDisabled(cells[i])) {
        i++
        continue
      }
      let j = i
      while (j + 1 < cells.length && this.isCellDisabled(cells[j + 1])) j++
      bands.push({
        key: 'b' + i,
        left: pct(open + i * SLOT_MINUTES),
        width: pct((j - i + 1) * SLOT_MINUTES)
      })
      i = j + 1
    }

    // 刻度：一小时一条。0 点和 24 点落在条的两端（被圆角裁掉半根），所以只画 1~23，
    // 末端 24 点的位置留给「次日」两个字。
    // 线色跟着底色走：营业/已占/已选都是中深底 → 白线；非营业是浅灰底 → 灰线，
    // 否则白线在 #e9edf4 上等于没画。
    const marks = []
    for (let h = 1; h <= 23; h++) {
      const mins = h * 60
      marks.push({
        k: 'm' + h,
        left: pct(mins),
        tone: mins >= open && mins <= close ? 'light' : 'mute'
      })
    }

    // 数字：每 2 小时一个（0/2/…/22），24 点换成换行的「次日」（见 wxml）。
    // 每小时都标在 313px 的条宽上只有 13px 一格，两位数会连成一片——
    // 刻度线负责「密」，数字负责「读」，两者不能都密。
    const labels = []
    for (let h = 0; h <= 22; h += 2) {
      labels.push({ k: 'l' + h, text: String(h), left: pct(h * 60) })
    }

    this.setData({
      bizLeft: pct(open),
      bizWidth: pct(close - open),
      ovBands: bands,
      ovMarks: marks,
      ovLabels: labels,
      hasSlots: cells.some((c) => !this.isCellDisabled(c))
    })
    this.syncOverviewSelection()
  },

  /** 概览条上的浅蓝「已选」段，跟着选区走 */
  syncOverviewSelection() {
    const { startIdx, endIdx } = this.data
    const open = toMinutes(this.data.openTime)
    if (startIdx < 0 || endIdx <= startIdx) {
      this.setData({ ovSelLeft: 0, ovSelWidth: 0 })
      return
    }
    this.setData({
      ovSelLeft: ((open + startIdx * SLOT_MINUTES) / DAY_MINUTES * 100).toFixed(3),
      ovSelWidth: ((endIdx - startIdx) * SLOT_MINUTES / DAY_MINUTES * 100).toFixed(3)
    })
  },

  /** 找 idx 所在的连续空闲段 [i0, i1]（闭区间）；idx 本身不可用时返回 null */
  findRun(idx) {
    const cells = this.data.cells || []
    if (idx < 0 || idx >= cells.length) return null
    if (this.isCellDisabled(cells[idx])) return null
    let a = idx
    let b = idx
    while (a - 1 >= 0 && !this.isCellDisabled(cells[a - 1])) a--
    while (b + 1 < cells.length && !this.isCellDisabled(cells[b + 1])) b++
    return { i0: a, i1: b }
  },

  /** 90 -> "1 小时 30 分"；不足 1 小时只给分钟 */
  formatDuration(mins) {
    const h = Math.floor(mins / 60)
    const m = mins % 60
    if (h && m) return h + ' 小时 ' + m + ' 分'
    if (h) return h + ' 小时'
    return m + ' 分钟'
  },

  /**
   * 落地一次选择。
   *
   * 时间文字、时段框、概览条的已选段三处全在这里算，避免各算一遍对不上。
   * 入参是槽位下标，区间 [s, e)，真实结束时刻 = 营业开始 + e * 15 分钟。
   */
  applySelection(s, e) {
    const open = toMinutes(this.data.openTime)
    const startTxt = formatHM(open + s * SLOT_MINUTES)
    const endTxt = formatHM(open + e * SLOT_MINUTES)
    this.setData({
      startIdx: s,
      endIdx: e,
      selectedStart: startTxt,
      selectedEnd: endTxt,
      durationText: this.formatDuration((e - s) * SLOT_MINUTES),
      canSubmit: true
    })
    this.syncOverviewSelection()
  },

  /**
   * 确定当前选择，优先级：
   *   1. 编辑模式 / 换日期前记下的原时段，且整段仍空闲 → 原样恢复
   *   2. 现有选择仍然合法 → 保留（换日期后相同时段不用重选）
   *   3. 否则取第一个空档的前 1 小时（空档不足 1 小时就给整段）
   * 没有第 3 步的话，一进页面就是空，用户还得先点两次滚轮才能提交。
   */
  ensureSelection(preferStart, preferEnd) {
    const cells = this.data.cells || []
    const open = toMinutes(this.data.openTime)
    const toIdx = (t) => Math.round((toMinutes(t) - open) / SLOT_MINUTES)

    if (preferStart && preferEnd) {
      const s = toIdx(preferStart)
      const e = toIdx(preferEnd)
      if (this.isRangeFree(s, e)) {
        this.applySelection(s, e)
        return
      }
    }

    const cur = this.data
    if (this.isRangeFree(cur.startIdx, cur.endIdx)) {
      this.applySelection(cur.startIdx, cur.endIdx)
      return
    }

    const first = cells.findIndex((c) => !this.isCellDisabled(c))
    if (first < 0) {
      this.setData({
        startIdx: -1,
        endIdx: -1,
        durationText: '',
        selectedStart: '',
        selectedEnd: '',
        canSubmit: false
      })
      this.syncOverviewSelection()
      return
    }
    const run = this.findRun(first)
    this.applySelection(run.i0, Math.min(run.i1 + 1, run.i0 + 4))
  },

  /** 弹层打开时拦掉背后的页面滚动 */
  noop() {},

  /** 时列：营业开始 ~ 打烊那一小时（打烊整点本身要能选，所以是闭区间） */
  hourOptionsFor() {
    const from = Number(this.data.openTime.slice(0, 2))
    const to = Number(this.data.closeTime.slice(0, 2))
    const arr = []
    for (let h = from; h <= to; h++) arr.push(pad(h))
    return arr
  },

  /**
   * 分列：固定 00/15/30/45，但打烊那一小时要按打烊时刻截断。
   * 例如 22:00 打烊 → 选到 22 点时分钟只剩 00，滚不出 22:15。
   */
  minutesFor(hourStr) {
    const closeMin = toMinutes(this.data.closeTime)
    const h = Number(hourStr)
    const allowed = MINUTE_OPTIONS.filter((m) => h * 60 + Number(m) <= closeMin)
    return allowed.length ? allowed : ['00']
  },

  /** 点时间框：把滚轮拨到当前值再弹出来 */
  openTimePicker(e) {
    const which = e.currentTarget.dataset.which === 'end' ? 'end' : 'start'
    const cur = which === 'end' ? this.data.selectedEnd : this.data.selectedStart
    const fallback = which === 'end' ? this.data.closeTime : this.data.openTime
    const t = cur || fallback

    const hours = this.hourOptionsFor()
    const hh = t.slice(0, 2)
    const mins = this.minutesFor(hh)
    const mi = mins.indexOf(t.slice(3, 5))

    this.setData({
      pickerOpen: true,
      pickerWhich: which,
      pickerTitle: which === 'end' ? '选择结束时间' : '选择开始时间',
      hourOptions: hours,
      minuteOptions: mins,
      pickerValue: [Math.max(0, hours.indexOf(hh)), mi < 0 ? 0 : mi]
    })
  },

  onPickerChange(e) {
    const val = e.detail.value
    const hour = this.data.hourOptions[val[0]]
    if (!hour) return
    const mins = this.minutesFor(hour)
    const sameMins = mins.join() === this.data.minuteOptions.join()
    const patch = {
      // 分钟列变短时索引会越界，必须一起 clamp
      pickerValue: [val[0], sameMins ? Math.min(val[1], mins.length - 1) : 0]
    }
    if (!sameMins) patch.minuteOptions = mins
    this.setData(patch)
  },

  cancelTimePicker() {
    this.setData({ pickerOpen: false })
  },

  /**
   * 确认滚轮取值。
   *
   * 校验只看槽位：起点落在占用格里、终点不晚于起点、区间内有占用，全部当场拦下并给出原因。
   * 改开始时间时若把结束顶到了前面，自动顺延到「1 小时后」或当前空档末尾 ——
   * 否则用户得先改开始再回来改结束，两次操作才能完成一个动作。
   */
  confirmTimePicker() {
    const h = this.data.hourOptions[this.data.pickerValue[0]]
    const m = this.data.minuteOptions[this.data.pickerValue[1]]
    if (!h || !m) return

    const cells = this.data.cells || []
    const open = toMinutes(this.data.openTime)
    const idx = Math.round((toMinutes(h + ':' + m) - open) / SLOT_MINUTES)
    const which = this.data.pickerWhich

    if (which === 'start') {
      if (idx < 0 || idx >= cells.length) {
        wx.showToast({ title: '不在营业时间内', icon: 'none' })
        return
      }
      if (this.isCellDisabled(cells[idx])) {
        wx.showToast({ title: '该时段已被占用', icon: 'none' })
        return
      }
      let e = this.data.endIdx
      if (e <= idx) {
        const run = this.findRun(idx)
        e = Math.min(idx + 4, run ? run.i1 + 1 : idx + 1)
      }
      this.applySelection(idx, e)
    } else {
      if (this.data.startIdx < 0) {
        wx.showToast({ title: '请先选择开始时间', icon: 'none' })
        return
      }
      if (idx <= this.data.startIdx) {
        wx.showToast({ title: '结束时间要晚于开始时间', icon: 'none' })
        return
      }
      if (!this.isRangeFree(this.data.startIdx, idx)) {
        wx.showToast({ title: '这段时间已被占用', icon: 'none' })
        return
      }
      this.applySelection(this.data.startIdx, idx)
    }

    this.setData({ pickerOpen: false })
  },

  onSubjectInput(e) {
    this.setData({ subject: e.detail.value })
  },

  loadBooked() {
    // 编辑模式必须排除自己那条：否则原时段会被算成「已占用」，用户连自己原来的时间都选不回来
    const excludeId = this.data.isEdit ? this.data.editId : null
    // 重算前先把当前选择记下来，重建候选项时按它回填
    const prevStart = this.data.selectedStart
    const prevEnd = this.data.selectedEnd
    api.available(this.data.roomId, this.data.selectedDate, excludeId).then((list) => {
      const start = toMinutes(this.data.openTime)
      const end = toMinutes(this.data.closeTime)
      // 基于现有 cells 重建可用性，保留 idx / time 等字段
      const cells = this.data.cells.map((c) => (
        Object.assign({}, c, { booked: false, buffer: false, past: false })
      ))
      // 标记占用
      ;(list || []).forEach((b) => {
        const bs = toMinutes(b.startTime.substring(11, 16))
        const be = toMinutes(b.endTime.substring(11, 16))
        const si = (bs - start) / SLOT_MINUTES
        const ei = Math.ceil((be - start) / SLOT_MINUTES)
        for (let i = Math.max(0, si); i < Math.min(cells.length, ei); i++) {
          cells[i].booked = true
        }
      })
      // 标记缓冲（占用格前后各 GAP_MINUTES，clamp 到营业时间内）
      ;(list || []).forEach((b) => {
        const bs = toMinutes(b.startTime.substring(11, 16))
        const be = toMinutes(b.endTime.substring(11, 16))
        // 前缓冲：开始前 gap 分钟内
        const preStart = Math.max(start, bs - GAP_MINUTES)
        const preEnd = bs
        // 后缓冲：结束后 gap 分钟内
        const postStart = be
        const postEnd = Math.min(end, be + GAP_MINUTES)
        for (let m = preStart; m < preEnd; m += SLOT_MINUTES) {
          const i = (m - start) / SLOT_MINUTES
          if (i >= 0 && i < cells.length && !cells[i].booked) {
            cells[i].buffer = true
          }
        }
        for (let m = postStart; m < postEnd; m += SLOT_MINUTES) {
          const i = (m - start) / SLOT_MINUTES
          if (i >= 0 && i < cells.length && !cells[i].booked) {
            cells[i].buffer = true
          }
        }
      })
      // 标记已过（仅今天）
      const today = formatDate(new Date())
      if (this.data.selectedDate === today) {
        const now = new Date()
        const nowMin = now.getHours() * 60 + now.getMinutes()
        for (let m = start, i = 0; m < end; m += SLOT_MINUTES, i++) {
          if (m <= nowMin && !cells[i].booked && !cells[i].buffer) {
            cells[i].past = true
          }
        }
      }
      this.setData({ cells: cells })
      // 占用刷新后重建概览条：编辑模式靠 prevStart / prevEnd 把原时段重新选回来
      this.buildOverview()
      this.ensureSelection(prevStart, prevEnd)
      // 今天（尤其过了打烊时间）常常一个可选时段都没有，一进来就空态会让人以为坏了。
      // 只自动跳一次，跳过去之后无论有没有空都停在那儿，不再连环跳。
      if (!this.data.isEdit && !this.autoSkipped && !this.data.hasSlots &&
          this.data.selectedDate === this.todayStr) {
        const idx = (this.dateList || []).indexOf(this.data.selectedDate)
        const next = this.dateList[idx + 1]
        if (next) {
          this.autoSkipped = true
          wx.showToast({ title: '今天已无可预约时段，已切到明天', icon: 'none' })
          this.applyDate(next)
          return
        }
      }
    }).catch(() => {
      this.initSlots()
    })
  },

  /**
   * 提交。
   *
   * selectedEnd 现在存的就是「真实结束时刻」（滚轮里显示的也是它），
   * 不再像上一版那样存槽位再 +15 分钟，所以这里可以直接用。
   */
  submit() {
    const { selectedStart, selectedEnd, selectedDate, roomId, subject } = this.data
    if (!selectedStart || !selectedEnd) {
      wx.showToast({ title: '请选择时间段', icon: 'none' })
      return
    }

    // 会议主题必填
    const trimmedSubject = (subject || '').trim()
    if (!trimmedSubject) {
      wx.showToast({ title: '请输入会议主题', icon: 'none' })
      return
    }

    const data = {
      roomId: roomId,
      startTime: `${selectedDate} ${selectedStart}:00`,
      endTime: `${selectedDate} ${selectedEnd}:00`,
      subject: trimmedSubject
    }

    if (this.data.isEdit) {
      this.doUpdate(data)
      return
    }

    wx.showLoading({ title: '提交中', mask: true })
    api.createBooking(data).then(() => {
      wx.hideLoading()
      wx.showToast({ title: '预约成功', icon: 'success' })
      // 预约成功后请求订阅消息授权（临近提醒/违约/权限通知）
      this.requestSubscribe()
      setTimeout(() => {
        wx.switchTab({ url: '/pages/booking/my' })
      }, 600)
    }).catch((err) => {
      wx.hideLoading()
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  /**
   * 保存修改。
   *
   * 后端对「已通过的预约改了时间/会议室」会把状态退回待审批，
   * 这里按返回值判断，退回时要明确告诉用户 —— 只说"修改成功"，
   * 用户回头看到状态从"已通过"变"待审批"会以为出了故障。
   */
  doUpdate(data) {
    wx.showLoading({ title: '保存中', mask: true })
    api.updateBooking(this.data.editId, data).then((booking) => {
      wx.hideLoading()
      const backToPending = booking && booking.status === 0
      wx.showModal({
        title: backToPending ? '已提交重新审核' : '修改成功',
        content: backToPending
          ? '会议时间或会议室已变更，需要管理员重新审核，审核通过后即可扫码签到。'
          : '预约信息已更新。',
        showCancel: false,
        confirmText: '知道了',
        success: () => {
          if (backToPending) {
            // 退回待审批后，列表若还停在「已通过」，用户会以为预约凭空消失了。
            // 直接改上一页的筛选状态，返回时 onShow 会按待审批重新拉取。
            this.switchPrevListToPending()
          }
          wx.navigateBack()
        }
      })
    }).catch((err) => {
      wx.hideLoading()
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  /** 把「我的预约」切到待审批（只在从列表页进来时生效） */
  switchPrevListToPending() {
    const pages = getCurrentPages()
    const prev = pages[pages.length - 2]
    if (prev && prev.route && prev.route.indexOf('booking/my') > -1) {
      prev.setData({ currentStatus: 0, emptyText: '暂无待审批的预约' })
    }
  },

  /**
   * 拉取订阅消息模板 ID。
   *
   * 订阅消息是「一次授权 = 一次下发额度」：用户没在小程序里点过授权，
   * 后端就算配好了模板 ID 也发不出去（微信返回 43101）。
   * 模板 ID 统一由后端配置下发，前端不再维护第二份，避免两边不一致。
   * 任何失败都静默忽略 —— 订阅是增强能力，不能影响预约本身。
   */
  loadSubscribeTemplates() {
    api.subscribeTemplates()
      .then((res) => {
        const ids = (res && res.tmplIds) || []
        if (ids.length) {
          this.setData({ subscribeTemplates: ids })
        }
      })
      .catch(() => {})
  },

  /**
   * 请求订阅消息授权（一次授权 = 一次下发额度）。
   * 模板 ID 由后端下发，空则跳过（后台尚未申请模板时不打扰用户）。
   */
  requestSubscribe() {
    const tmplIds = this.data.subscribeTemplates || []
    if (!tmplIds.length) {
      return
    }
    wx.requestSubscribeMessage({
      tmplIds,
      success: (res) => {
        // accept / reject 都是用户的正常选择，不提示、不重试
        console.log('[subscribe] 授权结果', res)
      },
      fail: (err) => {
        console.warn('[subscribe] 授权失败', err)
      }
    })
  }
})
