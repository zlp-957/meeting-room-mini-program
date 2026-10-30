const { api } = require('../../utils/api')

const TYPE_TEXT = {
  1: '临近提醒',
  2: '违约通知',
  3: '权限通知',
  4: '预约变更'
}

Page({
  data: {
    list: [],
    page: 1,
    size: 10,
    hasMore: true,
    loading: false
  },

  onLoad() {
    this.load(true)
  },

  onShow() {
    // 从其它页面返回时刷新（可能已读状态变化）
    this.load(true)
  },

  onPullDownRefresh() {
    this.load(true).finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.hasMore && !this.data.loading) {
      this.load(false)
    }
  },

  load(reset) {
    const page = reset ? 1 : this.data.page
    this.setData({ loading: true })
    return api.notificationList(page, this.data.size).then((res) => {
      const records = (res && res.records) || []
      const mapped = records.map((n) => ({
        ...n,
        typeText: TYPE_TEXT[n.type] || '通知'
      }))
      const list = reset ? mapped : this.data.list.concat(mapped)
      this.setData({
        list,
        page: page + 1,
        hasMore: mapped.length >= this.data.size,
        loading: false
      })
    }).catch((err) => {
      this.setData({ loading: false })
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  onRead(e) {
    const { id, index } = e.currentTarget.dataset
    const item = this.data.list[index]
    if (item.isRead === 1) {
      return
    }
    api.readNotification(id).then(() => {
      const list = this.data.list.slice()
      list[index] = { ...list[index], isRead: 1 }
      this.setData({ list })
    }).catch(() => {})
  },

  readAll() {
    api.readAllNotification().then(() => {
      const list = this.data.list.map((n) => ({ ...n, isRead: 1 }))
      this.setData({ list })
      wx.showToast({ title: '已全部标记为已读', icon: 'success' })
    }).catch((err) => {
      wx.showToast({ title: err.message, icon: 'none' })
    })
  }
})
