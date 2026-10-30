/**
 * 无头验证「我的」页头像渲染链路。
 *
 * 背景：自基础库 3.8.7 起，image 组件在开发版/体验版不再加载 HTTP 协议的图片，
 * 而本地后端是 http://192.168.x.x:8080。直接把网络地址交给 image，真机上头像
 * 画不出来（开发者工具却正常），表现就是「上传成功但头像没变」。
 *
 * 本脚本用假的 wx 对象跑真实页面代码，断言：
 *   - http 头像必须走「下载 → 本地文件路径」再渲染，不能直接给 image
 *   - https 头像（生产）直接用，不做多余下载
 *   - 缓存命中且文件存在时不再下载；缓存文件被清理时会重新下载
 *   - 下载失败时回退文字头像，不崩
 *   - 图片加载失败只重试一次，仍失败才清空
 *   - 选图上传后立即用本地图渲染（不依赖网络图能否加载）
 *
 * 运行：node tools/_verify/verify_avatar_display.js
 */
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const PAGE_PATH = path.join(ROOT, 'miniprogram/pages/mine/mine.js')
const AVATAR = '/upload/avatar/202610/demo.jpg'
// 局域网地址从 env.js 读：改 LAN_IP 后用例不需要跟着改
const { LAN_IP, PORT } = require(path.join(ROOT, 'miniprogram/utils/env.js'))
const HTTP_URL = 'http://' + LAN_IP + ':' + PORT + AVATAR

let passed = 0
let failed = 0

function check(label, actual, expected) {
  const ok = actual === expected
  if (ok) {
    passed++
    console.log('  PASS  %s', label)
  } else {
    failed++
    console.log('  FAIL  %s\n        期望：%s\n        实际：%s', label, expected, actual)
  }
}

function checkPrefix(label, actual, prefix) {
  const ok = typeof actual === 'string' && actual.indexOf(prefix) === 0
  if (ok) {
    passed++
    console.log('  PASS  %s', label)
  } else {
    failed++
    console.log('  FAIL  %s\n        期望以 %s 开头\n        实际：%s', label, prefix, actual)
  }
}

/** 构造一套可控的 wx 模拟环境 */
function makeWx(opts) {
  const options = opts || {}
  const storage = new Map(options.storage || [])
  const files = new Set(options.files || [])
  const calls = []
  let seq = 0

  const wx = {
    _calls: calls,
    _storage: storage,
    _files: files,
    getStorageSync: (k) => (storage.has(k) ? storage.get(k) : ''),
    setStorageSync: (k, v) => storage.set(k, v),
    removeStorageSync: (k) => storage.delete(k),
    // 真机预览：非 devtools，走 LAN_IP，正好复现线上那种 http 地址
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'trial' } }),
    getDeviceInfo: () => ({ platform: 'android' }),
    getSystemInfoSync: () => ({ platform: 'android', windowWidth: 375 }),
    showLoading: () => {},
    hideLoading: () => {},
    showToast: (o) => calls.push({ name: 'toast', arg: o && o.title }),
    showModal: () => {},
    reLaunch: () => {},
    navigateTo: () => {},
    request: (o) => {
      calls.push({ name: 'request', arg: o && o.url, type: o && o.responseType })
      if (o && o.responseType === 'arraybuffer') {
        // 头像兜底路径：拿图片字节
        if (options.requestOk === false) {
          o.fail && o.fail({ errMsg: 'request:fail' })
          return
        }
        o.success && o.success({ statusCode: 200, data: new Uint8Array([0xff, 0xd8, 0xff, 0xdb]).buffer })
        return
      }
      o.success && o.success({ statusCode: 200, data: { code: 200, data: {} } })
    },
    arrayBufferToBase64: (buf) => Buffer.from(buf).toString('base64'),
    downloadFile: (o) => {
      calls.push({ name: 'downloadFile', arg: o && o.url })
      if (options.downloadOk === false) {
        o.fail && o.fail({ errMsg: 'downloadFile:fail' })
        return
      }
      seq++
      o.success && o.success({ statusCode: 200, tempFilePath: 'wxfile://tmp_' + seq })
    },
    saveFile: (o) => {
      const saved = 'wxfile://usr_saved_' + o.tempFilePath.split('_').pop()
      files.add(saved)
      o.success && o.success({ savedFilePath: saved })
    },
    getFileSystemManager: () => ({
      accessSync: (p) => {
        if (!files.has(p)) throw new Error('no such file')
      },
      unlinkSync: (p) => {
        files.delete(p)
      }
    })
  }
  return wx
}

/** 加载页面配置并造实例（data 深拷贝，setData 直接合并） */
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
  Object.keys(config).forEach((k) => {
    if (k !== 'data') instance[k] = config[k]
  })
  return instance
}

const tick = () => new Promise((r) => setTimeout(r, 20))

function downloads(wx) {
  return wx._calls.filter((c) => c.name === 'downloadFile').map((c) => c.arg)
}

;(async () => {
  // ---- 1) http 头像：必须落到本地文件 ----
  console.log('\n=== 1) HTTP 头像（真机场景）===')
  let wx = makeWx({})
  global.wx = wx
  let page = loadPage()
  page.refreshAvatar(AVATAR)
  await tick()
  check('发起一次下载', downloads(wx).length, 1)
  check('下载的就是拼接后的 http 地址', downloads(wx)[0], HTTP_URL)
  checkPrefix('渲染用本地文件，而不是 http 地址', page.data.avatarUrl, 'wxfile://')
  check('本地路径写入缓存', wx._storage.get('avatarFile:' + AVATAR) === page.data.avatarUrl, true)

  // ---- 2) https 头像：直接用，不下载 ----
  console.log('\n=== 2) HTTPS 头像（生产环境）===')
  wx = makeWx({})
  global.wx = wx
  page = loadPage()
  page.refreshAvatar('https://cdn.example.org/a.jpg')
  await tick()
  check('不做多余下载', downloads(wx).length, 0)
  check('直接用 https 地址', page.data.avatarUrl, 'https://cdn.example.org/a.jpg')

  // ---- 3) 缓存命中且文件存在：不重复下载 ----
  console.log('\n=== 3) 缓存命中 ===')
  const cachedPath = 'wxfile://usr_saved_9'
  wx = makeWx({
    storage: [['avatarFile:' + AVATAR, cachedPath]],
    files: [cachedPath]
  })
  global.wx = wx
  page = loadPage()
  page.refreshAvatar(AVATAR)
  await tick()
  check('不再下载', downloads(wx).length, 0)
  check('直接用缓存的本地路径', page.data.avatarUrl, cachedPath)

  // ---- 4) 缓存指向的文件被系统清理：重新下载 ----
  console.log('\n=== 4) 缓存失效 ===')
  wx = makeWx({ storage: [['avatarFile:' + AVATAR, 'wxfile://gone.jpg']], files: [] })
  global.wx = wx
  page = loadPage()
  page.refreshAvatar(AVATAR)
  await tick()
  check('发现文件不存在后重新下载', downloads(wx).length, 1)
  checkPrefix('仍然渲染本地文件', page.data.avatarUrl, 'wxfile://')

  // ---- 5a) downloadFile 失败：退到 data URI，仍能画出头像 ----
  console.log('\n=== 5a) 下载被拦 → data URI 兜底 ===')
  wx = makeWx({ downloadOk: false })
  global.wx = wx
  page = loadPage()
  let threw = null
  try {
    page.refreshAvatar(AVATAR)
    await tick()
  } catch (e) {
    threw = e
  }
  check('未抛异常', threw === null, true)
  check('确实退到了 request 取图', wx._calls.filter((c) => c.type === 'arraybuffer').length, 1)
  checkPrefix('渲染 data URI', page.data.avatarUrl, 'data:image/jpeg;base64,')
  check('data URI 写入独立缓存', !!wx._storage.get('avatarData:' + AVATAR), true)

  // ---- 5b) 两条路都不通：回退文字头像 ----
  console.log('\n=== 5b) 下载与读图都失败 ===')
  wx = makeWx({ downloadOk: false, requestOk: false })
  global.wx = wx
  page = loadPage()
  page.refreshAvatar(AVATAR)
  await tick()
  check('回退为空（由 wxml 显示文字头像）', page.data.avatarUrl, '')

  // ---- 6) 图片加载失败：先重试一次，再失败才清空 ----
  console.log('\n=== 6) image 加载失败兜底 ===')
  wx = makeWx({})
  global.wx = wx
  page = loadPage()
  page.data.userInfo = { avatar: AVATAR }
  page.data.avatarUrl = HTTP_URL
  page.refreshAvatar(AVATAR)
  await tick()
  const beforeRetry = downloads(wx).length
  page.onAvatarError()
  await tick()
  check('首次失败会清缓存并重试', downloads(wx).length, beforeRetry + 1)
  page.onAvatarError()
  await tick()
  check('连续失败后不再重试（防死循环）', downloads(wx).length, beforeRetry + 1)
  check('最终回退文字头像', page.data.avatarUrl, '')

  // ---- 7) 上传成功后立即用本地图渲染 ----
  console.log('\n=== 7) 上传后即时渲染 ===')
  wx = makeWx({})
  global.wx = wx
  page = loadPage()
  page.data.userInfo = {}
  page.showPickedAvatar(AVATAR, 'wxfile://tmp_pick')
  await tick()
  checkPrefix('立刻显示刚选的本地图', page.data.avatarUrl, 'wxfile://')
  check('新头像路径已缓存', !!wx._storage.get('avatarFile:' + AVATAR), true)

  console.log('\n结果：%d 项通过，%d 项失败', passed, failed)
  process.exit(failed === 0 ? 0 : 1)
})()
