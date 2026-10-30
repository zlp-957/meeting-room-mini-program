# -*- coding: utf-8 -*-
"""管理端首页「快捷操作」静态复刻页。

用途：管理端有 token 守卫，headless 一进站就被打回登录页，截不到 Dashboard。
这里按 `src/views/Dashboard.vue` 的真实结构 + 真实 CSS 数值生成静态页，
图标 path 从 node_modules/@element-plus/icons-vue 里原样取出 —— 顺序或漏项一眼可见。
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ADMIN = os.path.join(ROOT, 'admin-web')
ICON_PKG = os.path.join(ADMIN, 'node_modules/@element-plus/icons-vue/dist/index.js')
OUT_HTML = os.path.join(ROOT, 'tools/_verify/admin-quick.html')

# 顺序必须与 Dashboard.vue 的 quick-actions 一致
ITEMS = [
    ('OfficeBuilding', 'blue', '#2563eb', '会议室管理'),
    ('Calendar', 'green', '#059669', '预约管理'),
    ('User', 'orange', '#d97706', '用户管理'),
    ('Bell', 'purple', '#7c3aed', '公告管理'),
]


def icon_paths(src, name):
    """取某个图标组件里所有 <path d="...">。

    截断点必须用 `_defineComponent`（下一个组件的开头），不能用 `name: "`
    —— 组件内部还有 `__name: "office-building"` 这类字段，会把自己提前截断。
    """
    i = src.find('name: "%s"' % name)
    if i < 0:
        return []
    nxt = src.find('_defineComponent', i + 10)
    seg = src[i:nxt] if nxt > 0 else src[i:i + 3000]
    return re.findall(r'd:\s*"([^"]+)"', seg)


def main():
    src = io.open(ICON_PKG, encoding='utf-8').read()

    items = []
    for comp, color_cls, color, label in ITEMS:
        ds = icon_paths(src, comp)
        paths = ''.join('<path fill="currentColor" d="%s"/>' % d for d in ds)
        items.append(
            '<div class="quick-item">'
            '<div class="quick-icon %s"><svg viewBox="0 0 1024 1024">%s</svg></div>'
            '<span>%s</span></div>' % (color_cls, paths, label)
        )
        print('%-16s path 数 %d  →  %s' % (comp, len(ds), label))

    html = '''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
*, *::before, *::after { animation: none !important; }
body { background: #f5f7fa; font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
       padding: 24px; width: 640px; }
.quick-card { background: #fff; border: 1px solid #e4e7ed; border-radius: 4px;
              box-shadow: 0 1px 2px rgba(0,0,0,.03); }
.card-title { font-size: 16px; font-weight: 600; color: #1a2233; }
.quick-card .head { padding: 18px 20px; border-bottom: 1px solid #e4e7ed; }
.quick-card .body { padding: 20px; }
.quick-actions { display: flex; gap: 24px; }
.quick-item { display: flex; flex-direction: column; align-items: center; gap: 10px;
              cursor: pointer; padding: 20px 30px; border-radius: 12px; }
.quick-icon { width: 48px; height: 48px; border-radius: 12px; display: flex;
              align-items: center; justify-content: center; font-size: 24px; }
.quick-icon svg { width: 24px; height: 24px; }
.quick-icon.blue   { background: #eff6ff; color: #2563eb; }
.quick-icon.green  { background: #ecfdf5; color: #059669; }
.quick-icon.purple { background: #f5f3ff; color: #7c3aed; }
.quick-icon.orange { background: #fff7ed; color: #d97706; }
.quick-item span { font-size: 14px; color: #475569; }
</style></head>
<body>
  <div class="quick-card">
    <div class="head"><span class="card-title">快捷操作</span></div>
    <div class="body"><div class="quick-actions">%s</div></div>
  </div>
</body></html>''' % ''.join(items)

    io.open(OUT_HTML, 'w', encoding='utf-8').write(html)
    print('写出', OUT_HTML)


if __name__ == '__main__':
    main()
