const { api } = require('../../utils/api')
const { detectBaseUrl } = require('../../utils/config')
const { LAN_IP, PORT } = require('../../utils/env')

// 开发者工具里每次 wx.login 的 code 都不同，mock 后会变成不同账号（编译一次多一个用户）
// 这里用固定 code，保证开发者工具里始终是同一个测试账号
const DEV_USER_CODE = 'devtools-demo-user'

Page({
  data: {
    loading: false,
    devEnv: false,
    baseUrl: '',
    features: [
      { icon: '🕐', text: '实时查看每间会议室的空闲时段', delay: 150 },
      { icon: '📋', text: '在线预约，审批进度随时可查', delay: 300 },
      { icon: '📍', text: '到点签到，让资源不被浪费', delay: 450 }
    ]
  },

  onLoad() {
    this.detectEnv()
    this.setData({ baseUrl: detectBaseUrl() })
    // 已登录直接进首页
    if (wx.getStorageSync('token')) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },

  /**
   * 长按 Logo 修改后端地址（真机调试换网络时用，避免改代码）
   */
  editServer() {
    const current = detectBaseUrl()
    wx.showModal({
      title: '后端服务地址',
      editable: true,
      content: current,
      placeholderText: 'http://' + LAN_IP + ':' + PORT,
      confirmText: '保存',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        const val = String(res.content || '').trim().replace(/\/+$/, '')
        if (!val) {
          wx.removeStorageSync('apiBaseUrl')
          this.setData({ baseUrl: detectBaseUrl() })
          wx.showToast({ title: '已恢复默认', icon: 'none' })
          return
        }
        if (!/^https?:\/\//.test(val)) {
          wx.showToast({ title: '地址需以 http:// 开头', icon: 'none' })
          return
        }
        wx.setStorageSync('apiBaseUrl', val)
        this.setData({ baseUrl: val })
        wx.showToast({ title: '已保存', icon: 'success' })
      }
    })
  },

  detectEnv() {
    let envVersion = 'release'
    try {
      const acc = wx.getAccountInfoSync ? wx.getAccountInfoSync() : null
      envVersion = (acc && acc.miniProgram && acc.miniProgram.envVersion) || 'release'
    } catch (e) {
      envVersion = 'release'
    }
    this.setData({ devEnv: envVersion === 'develop' })
  },

  /**
   * 微信授权登录：只换取 openid 建立登录态，不强制绑定手机号
   * 手机号在「我的」页面按需绑定
   */
  handleWxLogin() {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })

    wx.login({
      success: (res) => {
        const code = this.data.devEnv ? DEV_USER_CODE : res.code
        api.wxLogin(code).then((data) => {
          wx.setStorageSync('token', data.token)
          wx.setStorageSync('userInfo', data.userInfo || {})
          wx.removeStorageSync('needBindPhone')
          wx.switchTab({ url: '/pages/index/index' })
        }).catch((err) => {
          wx.showToast({ title: err.message || '登录失败，请重试', icon: 'none' })
        }).finally(() => {
          this.setData({ loading: false })
        })
      },
      fail: () => {
        this.setData({ loading: false })
        wx.showToast({ title: '微信登录失败，请重试', icon: 'none' })
      }
    })
  }
})
