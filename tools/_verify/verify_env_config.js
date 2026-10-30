/**
 * 校验小程序后端地址解析逻辑（utils/config.js）。
 *
 * 目的：这一段决定「正式版能不能连上后端」，属于上线阻塞点，
 * 必须用确定性用例覆盖四种运行环境，而不是靠肉眼看代码。
 *
 * 运行：node tools/_verify/verify_env_config.js
 */
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const ENV_PATH = path.join(ROOT, 'miniprogram/utils/env.js')
const CFG_PATH = path.join(ROOT, 'miniprogram/utils/config.js')

/** 构造一个假的 wx 对象：platform + envVersion + 本地缓存 */
function setupWx({ platform = 'android', envVersion = 'develop', customBaseUrl = '' } = {}) {
  global.wx = {
    getDeviceInfo: () => ({ platform }),
    getSystemInfoSync: () => ({ platform }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion } }),
    getStorageSync: (k) => (k === 'apiBaseUrl' ? customBaseUrl : '')
  }
}

/** 清掉 require 缓存，让 env/config 用新的全局 wx 与新的 PROD_BASE_URL 重新求值 */
function loadConfig({ prodBaseUrl }) {
  delete require.cache[require.resolve(ENV_PATH)]
  delete require.cache[require.resolve(CFG_PATH)]
  const env = require(ENV_PATH)
  if (prodBaseUrl !== undefined) {
    env.PROD_BASE_URL = prodBaseUrl
  }
  return require(CFG_PATH)
}

// 局域网地址从 env.js 读，避免把 IP 写死在用例里（改 IP 后用例不该跟着改）
const CURRENT_LAN = require(ENV_PATH).LAN_IP
const LAN_BASE = 'http://' + CURRENT_LAN + ':8080'

const cases = []
function check(name, actual, expected) {
  const pass = actual === expected
  cases.push({ name, actual, expected, pass })
}

// 1. 开发者工具（develop + devtools）→ localhost
setupWx({ platform: 'devtools', envVersion: 'develop' })
check('开发者工具 → localhost',
  loadConfig({ prodBaseUrl: '' }).detectBaseUrl(),
  'http://localhost:8080')

// 2. 真机预览（trial + 手机）→ 局域网 IP
setupWx({ platform: 'android', envVersion: 'trial' })
check('真机预览 → 局域网 IP',
  loadConfig({ prodBaseUrl: 'https://meeting.test.cn' }).detectBaseUrl(),
  LAN_BASE)

// 3. 正式版 + 未配置域名 → 回落局域网（并告警），不能静默当成功
setupWx({ platform: 'android', envVersion: 'release' })
check('正式版未配域名 → 回落局域网',
  loadConfig({ prodBaseUrl: '' }).detectBaseUrl(),
  LAN_BASE)

// 4. 正式版 + 已配置 https 域名 → 用域名
setupWx({ platform: 'android', envVersion: 'release' })
check('正式版已配 https 域名 → 用域名',
  loadConfig({ prodBaseUrl: 'https://meeting.test.cn/' }).detectBaseUrl(),
  'https://meeting.test.cn')

// 5. 正式版 + http 域名 → 视为未配置（微信强制 https）
setupWx({ platform: 'android', envVersion: 'release' })
check('正式版配了 http → 视为未配置',
  loadConfig({ prodBaseUrl: 'http://meeting.test.cn' }).detectBaseUrl(),
  LAN_BASE)

// 6. 正式版 + 仍是示例域名 → 视为未配置
setupWx({ platform: 'android', envVersion: 'release' })
check('正式版仍是 example.com → 视为未配置',
  loadConfig({ prodBaseUrl: 'https://meeting.example.com' }).detectBaseUrl(),
  LAN_BASE)

// 7. 本地缓存优先于一切（登录页长按 Logo 场景）
setupWx({ platform: 'devtools', envVersion: 'release', customBaseUrl: 'http://10.0.0.9:9000/' })
check('缓存地址优先',
  loadConfig({ prodBaseUrl: 'https://meeting.test.cn' }).detectBaseUrl(),
  'http://10.0.0.9:9000')

let failed = 0
for (const c of cases) {
  if (c.pass) {
    console.log('PASS  %s  → %s', c.name, c.actual)
  } else {
    failed++
    console.log('FAIL  %s\n      期望 %s\n      实际 %s', c.name, c.expected, c.actual)
  }
}
console.log('\n合计 %d 项，通过 %d 项，失败 %d 项', cases.length, cases.length - failed, failed)
process.exit(failed === 0 ? 0 : 1)
