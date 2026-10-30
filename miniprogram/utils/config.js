// 后端服务地址解析
//
// 地址来源优先级（从高到低）：
//   1. 登录页长按 Logo 手动设置的值（存本地缓存 apiBaseUrl）—— 调试兜底
//   2. 正式版（release）→ utils/env.js 的 PROD_BASE_URL（https 域名）
//   3. 开发者工具 → localhost（工具里的 localhost 指向电脑，能直连）
//   4. 真机预览 / 真机调试 → utils/env.js 的 LAN_IP
//
// 需要改地址时只动 utils/env.js，不要改本文件。
const { LAN_IP, PORT, PROD_BASE_URL } = require('./env')

/** 小程序运行版本：develop 开发版 / trial 体验版 / release 正式版 */
function getEnvVersion() {
  try {
    const acc = wx.getAccountInfoSync ? wx.getAccountInfoSync() : null
    return (acc && acc.miniProgram && acc.miniProgram.envVersion) || 'release'
  } catch (e) {
    return 'release'
  }
}

function getPlatform() {
  try {
    if (wx.getDeviceInfo) {
      return wx.getDeviceInfo().platform || ''
    }
    return (wx.getSystemInfoSync().platform) || ''
  } catch (e) {
    return ''
  }
}

/** PROD_BASE_URL 是否已真正配置（空 或 仍是示例域名 都算没配） */
function hasProdBaseUrl() {
  const url = String(PROD_BASE_URL || '').trim()
  if (!url) return false
  if (!/^https:\/\//i.test(url)) return false
  if (/your-domain|example\.com/i.test(url)) return false
  return true
}

/**
 * 解析当前应使用的后端地址
 */
function detectBaseUrl() {
  const custom = wx.getStorageSync('apiBaseUrl')
  if (custom) {
    return String(custom).replace(/\/+$/, '')
  }
  if (getEnvVersion() === 'release') {
    if (hasProdBaseUrl()) {
      return String(PROD_BASE_URL).trim().replace(/\/+$/, '')
    }
    // 正式版没配域名：仍是硬伤，明确告警而不是静默连局域网 IP
    console.warn('[config] 正式版未配置 PROD_BASE_URL（须为 https 域名），当前回落到局域网地址，正式版将无法连接后端')
    return 'http://' + LAN_IP + ':' + PORT
  }
  if (getPlatform() === 'devtools') {
    return 'http://localhost:' + PORT
  }
  return 'http://' + LAN_IP + ':' + PORT
}

module.exports = {
  LAN_IP,
  PORT,
  PROD_BASE_URL,
  detectBaseUrl
}
