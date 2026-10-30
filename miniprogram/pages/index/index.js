const { api } = require('../../utils/api')

/** "09:00:00" -> "09:00" */
function hhmm(t) {
  return t ? String(t).substring(0, 5) : ''
}

Page({
  data: {
    allRooms: [],        // 接口返回的全部会议室
    rooms: [],           // 当前筛选结果
    minCapacity: 0,      // 已生效的人数条件（0 = 不限）
    filterCapacity: '',  // 面板里正在输入的人数（未生效）
    showFilter: false,   // 筛选面板是否展开
    loading: false
  },

  onShow() {
    // 登录守卫：未登录先去登录页，不允许直接进首页
    if (!wx.getStorageSync('token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadRooms()
  },

  loadRooms() {
    this.setData({ loading: true })
    api.roomList().then((list) => {
      const allRooms = (list || []).map((item) => ({
        ...item,
        openTimeText: hhmm(item.openTime),
        closeTimeText: hhmm(item.closeTime)
      }))
      this.setData({ allRooms, loading: false })
      this.applyFilter()
    }).catch((err) => {
      this.setData({ loading: false })
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  // 按已生效的人数条件过滤：容量 >= minCapacity
  applyFilter() {
    const { allRooms, minCapacity } = this.data
    const rooms = minCapacity > 0
      ? allRooms.filter((item) => Number(item.capacity) >= minCapacity)
      : allRooms
    this.setData({ rooms })
  },

  // 打开筛选面板，回填当前已生效的人数
  openFilter() {
    const { minCapacity } = this.data
    this.setData({
      showFilter: true,
      filterCapacity: minCapacity > 0 ? String(minCapacity) : ''
    })
  },

  closeFilter() {
    this.setData({ showFilter: false })
  },

  onCapacityInput(e) {
    this.setData({ filterCapacity: e.detail.value })
  },

  // 确定筛选
  onApplyFilter() {
    const raw = String(this.data.filterCapacity || '').trim()

    // 不填视为不限
    if (!raw) {
      this.setData({ showFilter: false, minCapacity: 0 }, () => this.applyFilter())
      return
    }

    const num = Number(raw)
    if (!Number.isFinite(num) || num <= 0) {
      wx.showToast({ title: '请输入有效的参会人数', icon: 'none' })
      return
    }

    this.setData({ showFilter: false, minCapacity: Math.floor(num) }, () => this.applyFilter())
  },

  // 重置为不限
  onResetFilter() {
    this.setData({ showFilter: false, minCapacity: 0, filterCapacity: '' }, () => {
      this.applyFilter()
    })
  },

  // 阻止弹层滚动穿透
  noop() {},

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/room/detail?id=${id}` })
  }
})
