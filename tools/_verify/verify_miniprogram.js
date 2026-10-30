/**
 * 小程序静态一致性检查（不依赖微信开发者工具，可随时跑）。
 *
 * 检查三类「不会报编译错误、只在运行时才出问题」的缺陷：
 *   1. WXML 里 bindtap/catchtap 等绑定的事件处理函数，在同名 .js 里是否存在
 *      —— 缺失时点击无反应，且没有任何报错；
 *   2. app.json 注册的页面，三件套（.js/.wxml/.json）是否齐全
 *      —— 缺文件时小程序直接白屏；
 *   3. tabBar 图标文件是否存在、是否有页面写了却未注册。
 *
 * 运行：node tools/_verify/verify_miniprogram.js
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const MP_DIR = path.join(ROOT, 'miniprogram')
const PAGES_DIR = path.join(MP_DIR, 'pages')

const problems = []

function walk(dir, suffix, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name)
    if (fs.statSync(full).isDirectory()) walk(full, suffix, out)
    else if (name.endsWith(suffix)) out.push(full)
  }
  return out
}

// ---------- 1. 事件绑定 ----------
function extractHandlers(wxml) {
  const names = new Set()
  const re = /\b(?:bind|catch)[a-zA-Z]+\s*=\s*"([^"]*)"/g
  let m
  while ((m = re.exec(wxml)) !== null) {
    const raw = m[1].trim()
    if (!raw || raw.includes('{{') || raw.includes('(')) continue
    for (const one of raw.split(',')) {
      const name = one.trim()
      if (name) names.add(name)
    }
  }
  return names
}

function hasMethod(js, name) {
  const patterns = [
    new RegExp('(^|[\\s,{])' + name + '\\s*\\(', 'm'),
    new RegExp('(^|[\\s,{])' + name + '\\s*:', 'm')
  ]
  return patterns.some((p) => p.test(js))
}

let wxmlCount = 0
let handlerCount = 0
for (const wxmlPath of walk(PAGES_DIR, '.wxml')) {
  const jsPath = wxmlPath.replace(/\.wxml$/, '.js')
  const rel = path.relative(ROOT, wxmlPath)
  if (!fs.existsSync(jsPath)) {
    problems.push(`${rel} 缺少同名 .js`)
    continue
  }
  wxmlCount++
  const handlers = extractHandlers(fs.readFileSync(wxmlPath, 'utf8'))
  const js = fs.readFileSync(jsPath, 'utf8')
  for (const h of handlers) {
    handlerCount++
    if (!hasMethod(js, h)) {
      problems.push(`${rel} 绑定了 ${h}，但 js 中没有该方法`)
    }
  }
}

// ---------- 2. 页面注册与文件完整性 ----------
const appJson = JSON.parse(fs.readFileSync(path.join(MP_DIR, 'app.json'), 'utf8'))
const pages = appJson.pages || []
for (const p of pages) {
  for (const ext of ['.js', '.wxml', '.json']) {
    if (!fs.existsSync(path.join(MP_DIR, p + ext))) {
      problems.push(`app.json 注册了 ${p}，但缺少 ${p + ext}`)
    }
  }
}

// ---------- 3. tabBar 图标 + 未注册页面 ----------
const tabList = (appJson.tabBar && appJson.tabBar.list) || []
for (const item of tabList) {
  for (const key of ['iconPath', 'selectedIconPath']) {
    const icon = item[key]
    if (icon && !fs.existsSync(path.join(MP_DIR, icon))) {
      problems.push(`tabBar「${item.text}」的 ${key} 文件不存在：${icon}`)
    }
  }
}
const registered = new Set(pages)
for (const wxmlPath of walk(PAGES_DIR, '.wxml')) {
  const rel = path.relative(MP_DIR, wxmlPath).replace(/\\/g, '/').replace(/\.wxml$/, '')
  if (!registered.has(rel)) {
    problems.push(`${rel} 存在页面文件但未在 app.json 注册`)
  }
}

console.log('扫描：%d 个 wxml / %d 个事件绑定 / %d 个注册页面 / %d 个 tabBar 项',
  wxmlCount, handlerCount, pages.length, tabList.length)
if (problems.length === 0) {
  console.log('PASS 未发现绑定缺失、页面缺文件或图标缺失')
  process.exit(0)
}
console.log('\n发现 %d 处问题：', problems.length)
for (const p of problems) console.log('  - ' + p)
process.exit(1)
