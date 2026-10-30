const { api } = require('../../utils/api')

/** "08:00:00" -> "08:00" */
function hhmm(t) {
  return t ? String(t).substring(0, 5) : ''
}

Page({
  data: {
    room: {},
    roomId: null
  },

  onLoad(options) {
    this.setData({ roomId: options.id })
    this.loadDetail()
  },

  loadDetail() {
    api.roomDetail(this.data.roomId).then((room) => {
      room.openTimeText = hhmm(room.openTime)
      room.closeTimeText = hhmm(room.closeTime)
      this.setData({ room })
    }).catch((err) => {
      wx.showToast({ title: err.message, icon: 'none' })
    })
  },

  goBooking() {
    const { roomId, room } = this.data
    wx.navigateTo({
      url: `/pages/booking/create?roomId=${roomId}&roomName=${room.name}`
    })
  }
})
