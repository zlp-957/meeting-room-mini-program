// 请求封装 + API 工具
const { detectBaseUrl } = require('./config')

/**
 * 封装 wx.request
 */
function request(options) {
  const token = wx.getStorageSync('token')
  const baseUrl = detectBaseUrl()
  return new Promise((resolve, reject) => {
    wx.request({
      url: baseUrl + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        'Authorization': token || ''
      },
      success: (res) => {
        if (res.statusCode === 200) {
          if (res.data.code === 200) {
            resolve(res.data.data)
          } else if (res.data.code === 401) {
            // 未登录，跳转登录页
            wx.removeStorageSync('token')
            wx.reLaunch({ url: '/pages/login/login' })
            reject(new Error(res.data.msg || '未登录'))
          } else {
            reject(new Error(res.data.msg || '请求失败'))
          }
        } else {
          reject(new Error('网络错误'))
        }
      },
      fail: (err) => {
        console.error('[request] 请求失败', baseUrl + options.url, err)
        reject(new Error('网络连接失败：' + baseUrl))
      }
    })
  })
}

/**
 * 封装 wx.uploadFile（multipart/form-data）
 *
 * 不能用 wx.request 传文件，只能走 uploadFile；它返回的 res.data 是字符串，需要自己解析。
 * Content-Type 由基础库自动带 boundary，手动设置反而会失败。
 */
function uploadFile(options) {
  const token = wx.getStorageSync('token')
  const baseUrl = detectBaseUrl()
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: baseUrl + options.url,
      filePath: options.filePath,
      name: options.name || 'file',
      header: {
        'Authorization': token || ''
      },
      success: (res) => {
        let body = null
        try {
          body = JSON.parse(res.data)
        } catch (e) {
          body = null
        }
        if (!body) {
          reject(new Error('上传失败（HTTP ' + res.statusCode + '）'))
          return
        }
        if (body.code === 200) {
          resolve(body.data)
          return
        }
        if (body.code === 401) {
          wx.removeStorageSync('token')
          wx.reLaunch({ url: '/pages/login/login' })
          reject(new Error(body.msg || '未登录'))
          return
        }
        reject(new Error(body.msg || '上传失败'))
      },
      fail: (err) => {
        console.error('[uploadFile] 上传失败', baseUrl + options.url, err)
        reject(new Error('网络连接失败：' + baseUrl))
      }
    })
  })
}

// ===== API 定义 =====
const api = {
  // 认证
  wxLogin: (code) => request({ url: '/api/auth/wx-login', method: 'POST', data: { code } }),
  // 手机号只作资料/联系方式，不做验证，也不作为登录方式（登录只走微信授权）
  bindPhone: (phone) => request({ url: '/api/auth/bind-phone', method: 'POST', data: { phone } }),

  // 个人资料（昵称 / 头像）
  profile: () => request({ url: '/api/user/profile' }),
  updateNickname: (nickname) => request({ url: '/api/user/nickname', method: 'PUT', data: { nickname } }),
  uploadAvatar: (filePath) => uploadFile({ url: '/api/user/avatar', filePath, name: 'file' }),

  // 会议室
  roomList: () => request({ url: '/api/room/list' }),
  roomDetail: (id) => request({ url: `/api/room/${id}` }),

  // 预约
  createBooking: (data) => request({ url: '/api/booking/create', method: 'POST', data }),
  myBookings: (status) => request({ url: `/api/booking/my${status != null ? '?status=' + status : ''}` }),
  bookingDetail: (id) => request({ url: `/api/booking/detail/${id}` }),
  updateBooking: (id, data) => request({ url: `/api/booking/update/${id}`, method: 'PUT', data }),
  cancelBooking: (id) => request({ url: `/api/booking/cancel/${id}`, method: 'POST' }),
  signBooking: (id, code) => request({ url: `/api/booking/sign/${id}`, method: 'POST', data: { code } }),
  // excludeId：编辑已有预约时排除自身，否则自己的时段会显示成「已占用」
  available: (roomId, date, excludeId) => request({
    url: `/api/booking/available/${roomId}?date=${date}${excludeId ? '&excludeId=' + excludeId : ''}`
  }),

  // 公告
  noticeList: () => request({ url: '/api/announcement/list' }),
  noticeDetail: (id) => request({ url: `/api/announcement/${id}` }),

  // 通知
  notificationList: (page, size) => request({ url: `/api/notification/list?page=${page}&size=${size}` }),
  notificationUnread: () => request({ url: '/api/notification/unread-count' }),
  readNotification: (id) => request({ url: `/api/notification/read/${id}`, method: 'POST' }),
  readAllNotification: () => request({ url: '/api/notification/read-all', method: 'POST' }),

  // 订阅消息模板 ID：由后端配置下发，避免前端再维护一份；未配置时返回空数组
  subscribeTemplates: () => request({ url: '/api/subscribe/templates' })
}

module.exports = {
  request,
  uploadFile,
  api
}
