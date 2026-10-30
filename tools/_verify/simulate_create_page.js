/**
 * 无头模拟小程序页面生命周期，用于定位「页面打开后数据没渲染」类问题。
 *
 * 不依赖微信开发者工具：用假的 wx / Page 全局对象加载页面 js，
 * 调用 onLoad，检查异常与最终 data。能把「onLoad 抛异常导致后续 setData 全没执行」
 * 这类问题在命令行里直接暴露出来。
 *
 * 运行：node tools/_verify/simulate_create_page.js
 */
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const PAGE_PATH = path.join(ROOT, 'miniprogram/pages/booking/create.js')

const calls = []
function record(name, args) {
  calls.push({ name, args })
}

/** 构造最小可用的 wx 模拟对象 */
function makeWx({ failRequest = true } = {}) {
  return {
    getStorageSync: (k) => (k === 'token' ? 'fake-token' : ''),
    setStorageSync: () => {},
    removeStorageSync: () => {},
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    getDeviceInfo: () => ({ platform: 'devtools' }),
    getSystemInfoSync: () => ({ platform: 'devtools', windowWidth: 375 }),
    showLoading: () => record('showLoading'),
    hideLoading: () => record('hideLoading'),
    showToast: (o) => record('showToast', o && o.title),
    showModal: (o) => record('showModal', o && o.title),
    setNavigationBarTitle: () => {},
    navigateBack: () => record('navigateBack'),
    navigateTo: () => {},
    switchTab: () => {},
    reLaunch: () => {},
    request: (o) => {
      record('request', o && o.url)
      // 默认模拟「网络不可达」：真机上 IP 写错就是这种表现
      if (failRequest) {
        o.fail && o.fail({ errMsg: 'request:fail' })
      } else {
        o.success && o.success({ statusCode: 200, data: { code: 200, data: [] } })
      }
    },
    requestSubscribeMessage: () => record('requestSubscribeMessage')
  }
}

/** 捕获 Page() 注册的配置，并造一个可调用的页面实例 */
function loadPage() {
  let config = null
  global.Page = (c) => {
    config = c
  }
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  if (!config) throw new Error('页面未调用 Page() 注册')

  const instance = Object.create(null)
  instance.data = JSON.parse(JSON.stringify(config.data || {}))
  instance.setData = function (patch) {
    Object.assign(this.data, patch)
  }
  for (const [k, v] of Object.entries(config)) {
    if (k !== 'data') instance[k] = v
  }
  return instance
}

async function run(label, options) {
  calls.length = 0
  global.wx = makeWx()
  let page
  let error = null
  try {
    page = loadPage()
    page.onLoad(options)
  } catch (e) {
    error = e
  }
  // 等一轮宏任务，让 api 的 .then/.catch 回调真正执行完，
  // 否则读到的是「请求还没回来」的中间态，会误判成 cells 为空
  await new Promise((r) => setTimeout(r, 30))
  return { label, page, error, calls: calls.slice() }
}

function report(r) {
  console.log('\n=== %s ===', r.label)
  if (r.error) {
    console.log('  onLoad 抛异常：%s: %s', r.error.name, r.error.message)
  } else {
    console.log('  onLoad 正常返回')
  }
  if (!r.page) return
  const d = r.page.data
  console.log('  dateItems 条数：%d', (d.dateItems || []).length)
  console.log('  selectedDate：%s', d.selectedDate || '(空)')
  console.log('  openTime / closeTime：%s / %s', d.openTime, d.closeTime)
  console.log('  cells 条数：%d  hasSlots：%s', (d.cells || []).length, d.hasSlots)
  console.log('  发起的请求：%s', r.calls.filter((c) => c.name === 'request').map((c) => c.args).join(' , ') || '无')
  const toasts = r.calls.filter((c) => c.name === 'showToast').map((c) => c.args)
  if (toasts.length) console.log('  提示：%s', toasts.join(' / '))
}

;(async () => {
  const withRoom = await run('进入预约页（带 roomId=1，网络不可达）', { roomId: '1', roomName: '第一会议室' })
  report(withRoom)

  const noRoom = await run('进入预约页（缺少 roomId，模拟跳转参数丢失）', {})
  report(noRoom)

  // 断言：日期条必须恒为 14 张（本地生成，与网络无关），网络失败时 loadFailed 必须为 true
  let fail = 0
  for (const r of [withRoom, noRoom]) {
    if (r.error) { console.log('\n[FAIL] %s：onLoad 抛异常', r.label); fail++; continue }
    if ((r.page.data.dateItems || []).length !== 14) {
      console.log('\n[FAIL] %s：dateItems 应为 14，实际 %d', r.label, (r.page.data.dateItems || []).length)
      fail++
    }
    if (r.page.data.loadFailed !== true) {
      console.log('\n[FAIL] %s：请求失败时 loadFailed 应为 true，实际 %s', r.label, r.page.data.loadFailed)
      fail++
    }
    if ((r.page.data.cells || []).length === 0) {
      console.log('\n[FAIL] %s：即使网络失败也应生成默认时间格（08:00-22:00 = 56 格）', r.label)
      fail++
    }
  }
  console.log('\n断言结果：%s', fail === 0 ? 'PASS' : ('失败 ' + fail + ' 项'))
  process.exit(fail === 0 ? 0 : 1)
})()
