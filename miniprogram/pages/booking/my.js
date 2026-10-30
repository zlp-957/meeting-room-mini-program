const { api } = require('../../utils/api')

const STATUS_TEXT = { 0: '待审批', 1: '已通过', 2: '已驳回', 3: '已取消', 4: '已违约' }
const STATUS_BADGE = {
  0: 'badge-pending',
  1: 'badge-approved',
  2: 'badge-rejected',
  3: 'badge-canceled',
  4: 'badge-violated'
}
const STATUS_CLASS = {
  0: 'status-pending',
  1: 'status-approved',
  2: 'status-rejected',
  3: 'status-canceled',
  4: 'status-violated'
}

/** 与后端 meeting.cancel-before-hours 保持一致（仅在后端没下发 canCancel 时兜底） */
const CANCEL_BEFORE_HOURS = 2

/**
 * 签到 / 按钮状态的本地重算间隔（毫秒）。
 *
 * 用户可能停留在本页等待签到窗口开启，靠 onShow 触发刷新不够。
 * 这里只用后端下发的 signOpenAt / signDeadline 做时间比较，
 * 不含任何「提前多少分钟」的规则常量 —— 规则只有后端一份。
 */
const SIGN_TICK_MS = 30 * 1000

/**
 * P2-8：手动解析 "yyyy-MM-dd HH:mm:ss"
 * iOS 的 JSCore 对 new Date('2026/10/27T10:00:00') 可能返回 Invalid Date，
 * 这里按字段解析，避免依赖浏览器的宽松解析。
 */
function parseDateTime(str) {
  if (!str) return null
  const m = String(str).match(/(\d{4})-(\d{1,2})-(\d{1,2})[T ](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?/)
  if (!m) return null
  return new Date(
    Number(m[1]),
    Number(m[2]) - 1,
    Number(m[3]),
    Number(m[4]),
    Number(m[5]),
    Number(m[6] || 0)
  )
}

/** "2026-10-30 14:45:00" -> "14:45" */
function hm(str) {
  return str ? String(str).substring(11, 16) : ''
}

Page({
  data: {
    bookings: [],
    // 默认落在「待审批」：刚提交完预约就会跳到这里，能立刻看到自己那条
    currentStatus: 0,
    emptyText: '暂无待审批的预约',
    loading: false
  },

  onShow() {
    // 登录守卫：未登录回登录页
    if (!wx.getStorageSync('token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadBookings()
    this.startSignTicker()
  },

  onHide() {
    this.stopSignTicker()
  },

  onUnload() {
    this.stopSignTicker()
  },

  loadBookings() {
    this.setData({ loading: true })
    api.myBookings(this.data.currentStatus).then((list) => {
      const bookings = (list || []).map((b) => {
        b.statusText = STATUS_TEXT[b.status] || '未知'
        b.badgeClass = STATUS_BADGE[b.status] || ''
        b.statusClass = STATUS_CLASS[b.status] || ''
        b.timeMain = (b.startTime ? b.startTime.substring(11, 16) : '') + ' - ' + (b.endTime ? b.endTime.substring(11, 16) : '')
        b.timeDate = b.startTime ? b.startTime.substring(0, 10) : ''
        // P2-14：展示签到时间，签到后状态可见
        b.checkInText = b.checkInTime ? '已签到 ' + b.checkInTime.substring(11, 16) : ''
        // 取消 / 修改 / 签到 的可用性一律以后端下发为准，后端未下发时才本地兜底
        b.canCancel = b.canCancel === true || (b.canCancel == null && this.canCancelLocal(b))
        b.canEdit = b.canEdit === true || (b.canEdit == null && this.canCancelLocal(b))
        // canSign 是「入口」：审批通过、未签到、未超时就恒为 true，按钮常驻
        b.canSign = b.canSign === true
        // signWindowOpen 才是「此刻能不能真扫」，决定点击后是扫码还是提示几点开始
        b.signWindowOpen = b.signWindowOpen === true
        b.signOpenText = hm(b.signOpenAt)
        b.signDeadlineText = hm(b.signDeadline)
        b.signHintText = b.signWindowOpen
          ? '请扫描「' + (b.roomName || '会议室') + '」门口的二维码，' + b.signDeadlineText + ' 前有效'
          : '签到通道 ' + b.signOpenText + ' 开启，' + b.signDeadlineText + ' 截止'
        return b
      })
      this.setData({ bookings, loading: false })
    }).catch((err) => {
      this.setData({ loading: false })
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  switchTab(e) {
    const status = Number(e.currentTarget.dataset.status)
    this.setData({
      currentStatus: status,
      // 空列表说清楚「这个状态下没有」，比统一的"暂无预约记录"更少误会
      emptyText: '暂无' + (STATUS_TEXT[status] || '') + '的预约'
    })
    this.loadBookings()
  },

  /** 后端没下发 canCancel 时的本地兜底，正常路径不会走到 */
  canCancelLocal(b) {
    if (b.status !== 0 && b.status !== 1) return false
    if (b.checkInTime) return false
    const start = parseDateTime(b.startTime)
    if (!start) return false
    return (start.getTime() - Date.now()) > CANCEL_BEFORE_HOURS * 60 * 60 * 1000
  },

  /**
   * 本地重算签到按钮状态。
   *
   * 只用后端下发的 signOpenAt / signDeadline 做比较，不含业务规则常量。
   * 两个标记都要重算：窗口开启后 signWindowOpen 变 true（按钮由「提示几点开始」变成可扫），
   * 过了截止时刻 canSign 变 false（按钮消失，等定时任务把状态刷成已违约）。
   */
  recomputeSignFlags() {
    const list = this.data.bookings
    if (!list || !list.length) return
    const now = Date.now()
    let changed = false
    const next = list.map((b) => {
      if (b.status !== 1 || b.checkInTime) return b
      const open = parseDateTime(b.signOpenAt)
      const deadline = parseDateTime(b.signDeadline)
      if (!open || !deadline) return b
      const timeout = now > deadline.getTime()
      const canSign = !timeout
      const windowOpen = !timeout && now >= open.getTime()
      if (canSign === b.canSign && windowOpen === b.signWindowOpen) return b
      changed = true
      return Object.assign({}, b, { canSign, signWindowOpen: windowOpen })
    })
    if (changed) {
      this.setData({ bookings: next })
    }
  },

  startSignTicker() {
    this.stopSignTicker()
    this.signTimer = setInterval(() => this.recomputeSignFlags(), SIGN_TICK_MS)
  },

  stopSignTicker() {
    if (this.signTimer) {
      clearInterval(this.signTimer)
      this.signTimer = null
    }
  },

  /** 修改预约：进入预约页的编辑模式（复用同一套时间轴，规则只有一份） */
  edit(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/booking/create?id=' + id })
  },

  cancel(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '确认取消',
      content: '确定要取消这个预约吗？',
      success: (res) => {
        if (res.confirm) {
          api.cancelBooking(id).then(() => {
            wx.showToast({ title: '已取消', icon: 'success' })
            this.loadBookings()
          }).catch((err) => {
            wx.showToast({ title: err.message, icon: 'none' })
          })
        }
      }
    })
  },

  /**
   * 签到入口。
   *
   * 按钮从审批通过起就一直在，但窗口没开时先给提示而不是直接调起扫码 ——
   * 让用户白扫一次再被后端拒绝，比提前告诉他「几点开始」体验差得多。
   */
  sign(e) {
    const id = e.currentTarget.dataset.id
    const booking = this.data.bookings.find((b) => b.id === id)
    if (!booking) return

    if (!booking.signWindowOpen) {
      const openText = booking.signOpenText || ''
      wx.showModal({
        title: '签到通道还没开启',
        content: '本次会议的签到通道 ' + openText + ' 开启，' + booking.signDeadlineText +
          ' 截止。到时间后再点这里扫码即可，逾期未签到会判定违约。',
        showCancel: false,
        confirmText: '我知道了'
      })
      return
    }

    if (!wx.getStorageSync('signTipShown')) {
      wx.showModal({
        title: '现场扫码签到',
        content: '签到需扫描会议室门口张贴的二维码，相册里的图片无效。请在会议开始后 15 分钟内完成，逾期将判定违约。',
        confirmText: '开始扫码',
        cancelText: '取消',
        success: (res) => {
          if (!res.confirm) return
          wx.setStorageSync('signTipShown', 1)
          this.startScan(id)
        }
      })
      return
    }
    this.startScan(id)
  },

  /**
   * 调起扫码。
   *
   * onlyFromCamera 是关键：设为 true 后用户只能实时用相机对准二维码，
   * 相册里的截图选不了 —— 这样就堵住了「把二维码拍下来存到手机、人不在现场也能签到」。
   */
  startScan(id) {
    wx.scanCode({
      onlyFromCamera: true,
      scanType: ['qrCode'],
      success: (res) => {
        const code = ((res && res.result) || '').trim()
        // 廉价本地校验：非本系统的二维码不必打后端
        if (code.indexOf('MR|') !== 0) {
          wx.showToast({ title: '请扫描会议室门口的签到二维码', icon: 'none' })
          return
        }
        this.doSign(id, code)
      },
      fail: (err) => {
        const msg = (err && err.errMsg) || ''
        // 用户主动取消：静默返回，不打扰
        if (msg.indexOf('cancel') > -1) return
        if (msg.indexOf('auth') > -1 || msg.indexOf('permission') > -1 || msg.indexOf('deny') > -1) {
          wx.showModal({
            title: '需要相机权限',
            content: '签到需要调用摄像头扫描门口二维码，请在设置中允许使用相机。',
            confirmText: '去设置',
            success: (r) => {
              if (r.confirm) wx.openSetting()
            }
          })
          return
        }
        wx.showToast({ title: '扫码失败，请重试', icon: 'none' })
      }
    })
  },

  doSign(id, code) {
    wx.showLoading({ title: '签到中', mask: true })
    api.signBooking(id, code).then((booking) => {
      wx.hideLoading()
      wx.vibrateShort({ type: 'light' })
      wx.showToast({ title: '签到成功', icon: 'success' })
      // 用接口返回值做局部更新：卡片立刻显示「已签到 HH:mm」，不等列表请求往返
      const checkInTime = (booking && booking.checkInTime) || ''
      const bookings = this.data.bookings.map((b) => {
        if (b.id !== id) return b
        return Object.assign({}, b, {
          checkInTime,
          checkInText: checkInTime ? '已签到 ' + checkInTime.substring(11, 16) : b.checkInText,
          canSign: false,
          signWindowOpen: false
        })
      })
      this.setData({ bookings })
      this.loadBookings()
    }).catch((err) => {
      wx.hideLoading()
      wx.showToast({ title: err.message, icon: 'none' })
      // 失败也刷新：把「已超时被判违约」这类状态变化同步下来
      this.loadBookings()
    })
  }
})
