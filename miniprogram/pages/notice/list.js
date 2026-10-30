const { api } = require('../../utils/api')

Page({
  data: {
    notices: [],
    loading: false
  },

  onLoad() {
    this.loadNotices()
  },

  loadNotices() {
    this.setData({ loading: true })
    api.noticeList().then((list) => {
      this.setData({ notices: list || [], loading: false })
    }).catch((err) => {
      this.setData({ loading: false })
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/notice/detail?id=${id}` })
  }
})
