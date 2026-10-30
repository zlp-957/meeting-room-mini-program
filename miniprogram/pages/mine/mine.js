const { api } = require('../../utils/api')
const { detectBaseUrl } = require('../../utils/config')

/**
 * 头像本地缓存的 storage 前缀，key = 前缀 + 头像相对路径。
 *
 * <p>为什么要落本地文件：自基础库 3.8.7 起，image 组件在开发版/体验版不再加载 HTTP
 * 协议的图片（开发者工具能显示、真机静默失败），本地后端是 http://192.168.x.x:8080，
 * 直接把网络地址交给 image 在真机上永远画不出来。见 resolveAvatarSrc。</p>
 */
const AVATAR_CACHE_PREFIX = 'avatarFile:'

/** 头像 data URI 缓存前缀（downloadFile 也被拦时的最后一条路，见 resolveAvatarSrc） */
const AVATAR_DATA_PREFIX = 'avatarData:'

/** 按扩展名推断图片 MIME，拼接 data URI 用 */
function imageMime(url) {
  const ext = String(url).split('?')[0].split('.').pop().toLowerCase()
  if (ext === 'png') return 'image/png'
  if (ext === 'webp') return 'image/webp'
  return 'image/jpeg'
}

Page({
  data: {
    userInfo: {},
    avatarText: '微',
    avatarUrl: '',
    maskPhone: '',
    binding: false,
    showBindDialog: false,
    phoneInput: '',
    // 昵称弹窗
    showNickDialog: false,
    nicknameInput: '',
    savingNick: false,
    // 头像上传中
    uploadingAvatar: false,
    unreadCount: 0
  },

  onShow() {
    // 登录守卫：未登录回登录页
    if (!wx.getStorageSync('token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.refreshUserInfo()
    this.loadUnread()
  },

  refreshUserInfo() {
    // 先拿本地缓存渲染，避免进入页面时白屏
    this.applyUserInfo(wx.getStorageSync('userInfo') || {})
    // 再拉一次服务端，保证换设备 / 后台改过资料后昵称头像是最新的
    api.profile().then((info) => {
      if (!info) return
      wx.setStorageSync('userInfo', info)
      this.applyUserInfo(info)
    }).catch(() => {})
  },

  applyUserInfo(userInfo) {
    this.applyUserProfile(userInfo)
    this.refreshAvatar(this.data.userInfo.avatar)
  },

  /**
   * 只刷新文字类资料（昵称 / 手机号 / 文字头像），不碰 avatarUrl。
   *
   * <p>上传头像后本地已经有可直接渲染的图片路径，若这里顺手用网络地址覆盖 avatarUrl，
   * 真机上会因为 HTTP 拦截闪一下再变回文字头像。</p>
   */
  applyUserProfile(userInfo) {
    const info = userInfo || {}
    const maskPhone = info.phone
      ? info.phone.substring(0, 3) + '****' + info.phone.substring(7)
      : ''
    this.setData({
      userInfo: info,
      avatarText: (info.nickname || '微').substring(0, 1),
      maskPhone
    })
  },

  /**
   * 头像字段存的是相对路径（/upload/avatar/...），这里拼上后端地址；
   * 已经是完整 http(s) 地址（比如以后接了 CDN）则原样使用。
   */
  buildAvatarUrl(avatar) {
    if (!avatar) return ''
    if (/^https?:\/\//i.test(avatar)) return avatar
    return detectBaseUrl() + avatar
  },

  /**
   * 把「头像路径」解析成 image 组件真正能画出来的 src。
   *
   * <p>自基础库 3.8.7 起，image 组件在开发版/体验版不再加载 HTTP 协议的图片：
   * 开发者工具能显示、真机静默失败，而且「不校验合法域名」也管不到它。
   * 本地调试用的是 http://192.168.x.x:8080，于是头像在真机上永远显示不出来 ——
   * 这就是「上传成功但头像没变」的真正原因。</p>
   *
   * <p>三级取图，逐级降级：</p>
   * <ol>
   *   <li>https 地址（生产环境）→ 直接给 image，零额外请求；</li>
   *   <li>http 地址 → downloadFile 存成本地文件，再用本地路径渲染（一次之后走缓存）；</li>
   *   <li>downloadFile 也失败 → 用 wx.request 以 arraybuffer 取图转 data URI
   *       （data: 协议不受 http 限制，只要接口能通就画得出来）。</li>
   * </ol>
   */
  resolveAvatarSrc(avatar) {
    const netUrl = this.buildAvatarUrl(avatar)
    if (!netUrl) return Promise.resolve('')
    if (/^https:/i.test(netUrl)) return Promise.resolve(netUrl)

    const cacheKey = AVATAR_CACHE_PREFIX + avatar
    const cached = wx.getStorageSync(cacheKey)
    // 缓存路径可能已被系统清理，命中后要确认文件还在
    if (cached && this.fileExists(cached)) return Promise.resolve(cached)

    const dataKey = AVATAR_DATA_PREFIX + avatar
    const cachedData = wx.getStorageSync(dataKey)
    if (cachedData) return Promise.resolve(cachedData)

    return this.downloadAvatar(netUrl)
      .then((localPath) => localPath || this.requestAvatarAsDataUrl(netUrl))
      .then((src) => {
        if (!src) return ''
        if (src.indexOf('data:') === 0) {
          wx.setStorageSync(dataKey, src)
        } else {
          wx.setStorageSync(cacheKey, src)
        }
        return src
      })
  },

  /** 下载网络图并存到用户目录（临时文件随时会被系统清理，存下来下次进页面不用重下） */
  downloadAvatar(url) {
    return new Promise((resolve) => {
      wx.downloadFile({
        url,
        success: (res) => {
          if (res.statusCode !== 200 || !res.tempFilePath) {
            resolve('')
            return
          }
          wx.saveFile({
            tempFilePath: res.tempFilePath,
            success: (r) => resolve(r.savedFilePath || res.tempFilePath),
            fail: () => resolve(res.tempFilePath)
          })
        },
        fail: (err) => {
          console.warn('[头像] 下载失败', url, err && err.errMsg)
          resolve('')
        }
      })
    })
  },

  /** 同步判断本地文件是否还在（accessSync 失败即视为不存在） */
  fileExists(path) {
    try {
      wx.getFileSystemManager().accessSync(path)
      return true
    } catch (e) {
      return false
    }
  },

  /**
   * 最后一条取图路径：用 wx.request 拿图片字节，转成 data URI 交给 image。
   *
   * <p>data: 协议不涉及网络请求，不受 image 组件的 HTTP 限制。只要接口能通
   * （能登录、能上传就说明能通），头像就画得出来。</p>
   */
  requestAvatarAsDataUrl(url) {
    return new Promise((resolve) => {
      wx.request({
        url,
        responseType: 'arraybuffer',
        success: (res) => {
          if (res.statusCode !== 200 || !res.data) {
            resolve('')
            return
          }
          try {
            const base64 = wx.arrayBufferToBase64(res.data)
            resolve('data:' + imageMime(url) + ';base64,' + base64)
          } catch (e) {
            console.warn('[头像] 转 data URI 失败', e)
            resolve('')
          }
        },
        fail: (err) => {
          console.warn('[头像] 读取图片失败', url, err && err.errMsg)
          resolve('')
        }
      })
    })
  },

  /**
   * 按当前头像路径刷新渲染用的 src。
   *
   * <p>用递增序号做竞态保护：连续换头像时，先发出的请求可能后返回，
   * 不做判断就会把新头像盖回旧图。</p>
   */
  refreshAvatar(avatar) {
    if (!avatar) {
      this.setData({ avatarUrl: '' })
      return
    }
    const seq = (this._avatarSeq || 0) + 1
    this._avatarSeq = seq
    this.resolveAvatarSrc(avatar).then((src) => {
      if (seq !== this._avatarSeq) return
      this.setData({ avatarUrl: src })
      if (!src) console.warn('[头像] 没有可用的图片，回退文字头像：', avatar)
    })
  },

  /**
   * 图片加载失败（网络图被拦、本地文件被清理、后端地址变了）时的兜底。
   *
   * <p>不能直接清空 avatarUrl：真机上一次 HTTP 拦截就会把头像永久打成文字，
   * 表现成「改了半天没反应」。这里丢掉本地缓存重取一次。</p>
   *
   * <p>重试按「头像路径」记账，同一张图只重试一次：否则一旦图片本身有问题
   * （文件损坏、格式不支持），每次 binderror 都触发重取，会陷入死循环。</p>
   */
  onAvatarError() {
    const avatar = this.data.userInfo.avatar
    if (!avatar) {
      this.setData({ avatarUrl: '' })
      return
    }
    if (this._avatarRetriedFor === avatar) {
      // 同一张图已经重试过还是画不出来，放弃，回到文字头像
      console.warn('[头像] 重试后仍无法显示，回退文字头像：', this.data.avatarUrl)
      this.setData({ avatarUrl: '' })
      return
    }
    console.warn('[头像] 图片加载失败，重新获取：', this.data.avatarUrl)
    this._avatarRetriedFor = avatar
    wx.removeStorageSync(AVATAR_CACHE_PREFIX + avatar)
    wx.removeStorageSync(AVATAR_DATA_PREFIX + avatar)
    this.refreshAvatar(avatar)
  },

  goNotice() {
    wx.navigateTo({ url: '/pages/notice/list' })
  },

  goNotification() {
    wx.navigateTo({ url: '/pages/notification/list' })
  },

  loadUnread() {
    api.notificationUnread().then((res) => {
      this.setData({ unreadCount: (res && res.count) || 0 })
    }).catch(() => {})
  },

  /* ===================== 修改头像 ===================== */

  /**
   * 选图 → 上传 → 服务端更新并返回最新 userInfo
   */
  changeAvatar() {
    if (this.data.uploadingAvatar) return
    this.pickImage()
      .then((filePath) => {
        if (!filePath) return
        this.setData({ uploadingAvatar: true })
        wx.showLoading({ title: '上传中', mask: true })
        const prevAvatar = this.data.userInfo.avatar
        return api.uploadAvatar(filePath).then((res) => {
          wx.hideLoading()
          const info = (res && res.userInfo) || {}
          wx.setStorageSync('userInfo', info)
          this.applyUserProfile(info)
          // 关键：用刚选中的本地图立刻渲染。
          // 服务端返回的是 http 相对路径，真机 image 加载不了，直接用它渲染等于白传。
          this.showPickedAvatar(info.avatar, filePath)
          this.forgetAvatarCache(prevAvatar)
          wx.showToast({ title: '头像已更新', icon: 'success' })
        })
      })
      .catch((err) => {
        wx.hideLoading()
        const msg = (err && (err.errMsg || err.message)) || ''
        // 用户主动取消选图，不弹错误提示
        if (msg.indexOf('cancel') > -1) return
        wx.showToast({ title: err.message || '头像上传失败，请重试', icon: 'none' })
      })
      .finally(() => {
        this.setData({ uploadingAvatar: false })
      })
  },

  /**
   * 把刚选中的临时图存成新头像的本地缓存，并立即渲染。
   *
   * <p>上传成功后接口返回的是 {@code /upload/avatar/...} 相对路径，拼上局域网地址是 http，
   * 真机 image 组件不加载 HTTP 图片，直接把网络地址塞进 src 会出现「提示成功但头像没变」。
   * 选图时拿到的临时路径是本地文件，一定能画出来，先用它保证即时可见，
   * 再用服务端地址校准一次（覆盖多端切换的情况）。</p>
   */
  showPickedAvatar(avatar, tempPath) {
    const cacheKey = avatar ? AVATAR_CACHE_PREFIX + avatar : ''
    const finish = (localPath) => {
      const src = localPath || tempPath || ''
      if (cacheKey && src) wx.setStorageSync(cacheKey, src)
      this.setData({ avatarUrl: src })
      if (avatar) this.refreshAvatar(avatar)
    }
    if (!tempPath) {
      finish('')
      return
    }
    wx.saveFile({
      tempFilePath: tempPath,
      success: (r) => finish(r.savedFilePath || tempPath),
      fail: () => finish(tempPath)
    })
  },

  /** 换头像后清掉上一张的缓存（本地文件 + data URI），避免本地越积越多 */
  forgetAvatarCache(avatar) {
    if (!avatar) return
    wx.removeStorageSync(AVATAR_DATA_PREFIX + avatar)
    const key = AVATAR_CACHE_PREFIX + avatar
    const cached = wx.getStorageSync(key)
    if (!cached) return
    wx.removeStorageSync(key)
    try {
      wx.getFileSystemManager().unlinkSync(cached)
    } catch (e) {
      // 文件可能已被系统清理，忽略
    }
  },

  /**
   * 选一张图片，返回临时路径。优先 chooseMedia（新版），低版本基础库回退 chooseImage。
   */
  pickImage() {
    return new Promise((resolve, reject) => {
      if (wx.chooseMedia) {
        wx.chooseMedia({
          count: 1,
          mediaType: ['image'],
          sizeType: ['compressed'],
          sourceType: ['album', 'camera'],
          success: (res) => {
            const file = res.tempFiles && res.tempFiles[0]
            resolve(file ? file.tempFilePath : '')
          },
          fail: reject
        })
        return
      }
      wx.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['album', 'camera'],
        success: (res) => resolve((res.tempFilePaths && res.tempFilePaths[0]) || ''),
        fail: reject
      })
    })
  },

  /* ===================== 修改昵称 ===================== */

  openNickDialog() {
    this.setData({
      showNickDialog: true,
      nicknameInput: this.data.userInfo.nickname || ''
    })
  },

  onNicknameInput(e) {
    this.setData({ nicknameInput: e.detail.value })
  },

  closeNickDialog() {
    this.setData({ showNickDialog: false })
  },

  /**
   * 前端预校验：只管长度和明显的格式问题，拿到即时反馈；
   * 敏感词、冒充官方、广告引流等由后端统一判定（词库在服务端维护，前端不重复一份）。
   */
  validateNickname(value) {
    if (value.length < 2) return '昵称至少 2 个字符'
    if (value.length > 20) return '昵称最多 20 个字符'
    if (/^\d+$/.test(value)) return '昵称不能是纯数字'
    if (/(.)\1{4,}/.test(value)) return '昵称不能包含大量重复字符'
    if (!/^[\u4e00-\u9fa5\u3400-\u4dbfa-zA-Z0-9_\-. ]+$/.test(value)) {
      return '昵称只能包含中文、字母、数字、下划线、连字符和空格'
    }
    return ''
  },

  submitNickname() {
    if (this.data.savingNick) return
    const nickname = (this.data.nicknameInput || '').trim()
    const err = this.validateNickname(nickname)
    if (err) {
      wx.showToast({ title: err, icon: 'none' })
      return
    }
    if (nickname === (this.data.userInfo.nickname || '')) {
      this.setData({ showNickDialog: false })
      return
    }

    this.setData({ savingNick: true })
    api.updateNickname(nickname)
      .then((info) => {
        wx.setStorageSync('userInfo', info || {})
        this.applyUserInfo(info || {})
        wx.showToast({ title: '昵称已修改', icon: 'success' })
        this.setData({ showNickDialog: false })
      })
      .catch((e) => {
        wx.showToast({ title: e.message || '修改失败，请重试', icon: 'none' })
      })
      .finally(() => {
        this.setData({ savingNick: false })
      })
  },

  /**
   * 打开绑定/修改手机号弹窗
   */
  openBindDialog() {
    this.setData({
      showBindDialog: true,
      phoneInput: ''
    })
  },

  onPhoneInput(e) {
    this.setData({ phoneInput: e.detail.value })
  },

  closeBindDialog() {
    this.setData({ showBindDialog: false })
  },

  noop() {},

  /**
   * 提交绑定/修改手机号（只填号码，不做短信验证）
   *
   * 手机号在这里只是联系方式，不是登录凭据（登录只走微信授权），
   * 所以不需要验证码；前端只做格式校验，唯一性由后端 uk_phone 兜底。
   */
  submitBind() {
    if (this.data.binding) {
      return
    }
    const phone = (this.data.phoneInput || '').trim()
    if (!/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '请输入正确的11位手机号', icon: 'none' })
      return
    }
    this.setData({ binding: true })
    this.ensureLogin()
      .then(() => api.bindPhone(phone))
      .then((info) => {
        wx.setStorageSync('userInfo', info || {})
        wx.showToast({ title: this.data.userInfo.phone ? '修改成功' : '绑定成功', icon: 'success' })
        this.closeBindDialog()
        this.refreshUserInfo()
      })
      .catch((err) => {
        wx.showToast({ title: err.message || '操作失败，请重试', icon: 'none' })
      })
      .finally(() => {
        this.setData({ binding: false })
      })
  },

  /**
   * 绑定前确保有登录态，否则接口会 401
   */
  ensureLogin() {
    return new Promise((resolve, reject) => {
      if (wx.getStorageSync('token')) {
        resolve()
        return
      }
      wx.login({
        success: (res) => {
          api.wxLogin(res.code).then((data) => {
            wx.setStorageSync('token', data.token)
            wx.setStorageSync('userInfo', data.userInfo || {})
            resolve()
          }).catch(reject)
        },
        fail: () => reject(new Error('微信登录失败，请重试'))
      })
    })
  },

  logout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          wx.removeStorageSync('token')
          wx.removeStorageSync('userInfo')
          wx.removeStorageSync('needBindPhone')
          wx.reLaunch({ url: '/pages/login/login' })
        }
      }
    })
  }
})
