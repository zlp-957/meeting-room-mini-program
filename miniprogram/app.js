// 认证流程版本：改动登录/绑定逻辑后 +1，可让老登录态重新走一次登录流程
const AUTH_VERSION = 2
const AUTH_VERSION_KEY = 'authVersion'

const { detectBaseUrl } = require('./utils/config')

App({
  onLaunch() {
    const cachedVersion = wx.getStorageSync(AUTH_VERSION_KEY)
    if (cachedVersion !== AUTH_VERSION) {
      // 老版本登录态可能缺手机号/缺标记，清掉后重新登录
      wx.removeStorageSync('token')
      wx.removeStorageSync('userInfo')
      wx.removeStorageSync('needBindPhone')
      wx.setStorageSync(AUTH_VERSION_KEY, AUTH_VERSION)
    }
  },
  globalData: {
    get baseUrl() {
      return detectBaseUrl()
    }
  }
})
