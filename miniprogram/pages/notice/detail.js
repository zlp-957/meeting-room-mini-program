const { api } = require('../../utils/api')

Page({
  data: {
    notice: {}
  },

  onLoad(options) {
    api.noticeDetail(options.id).then((n) => {
      this.setData({ notice: n })
    }).catch((err) => {
      wx.showToast({ title: err.message, icon: 'none' })
    })
  }
})
