# -*- coding: utf-8 -*-
"""按 Layout.vue 的真实菜单顺序，复刻管理端侧边栏用于截图核对。

为什么不直接截真机：管理端路由有 token 守卫，且页面会请求 /api/admin/*，
headless 里拿到 401 就被打回登录页。所以这里只复刻侧边栏本身——
菜单项顺序、文字、颜色全部从 src/layout/Layout.vue 解析得来，样式数值照抄源码，
顺序对不上会立刻看出来。

用法：python tools/_verify/admin_sidebar_preview.py
"""
import io
import re

SRC = 'admin-web/src/layout/Layout.vue'
ACTIVE = '会议室管理'


def menu_items():
    s = io.open(SRC, encoding='utf-8').read()
    # 只取 el-menu 块内的 <span>文字</span>
    start = s.find('<el-menu')
    end = s.find('</el-menu>')
    block = s[start:end]
    return re.findall(r'<span>(.*?)</span>', block)


def main():
    items = menu_items()
    lis = '\n      '.join(
        '<li class="menu-item%s"><span class="ico"></span><span>%s</span></li>'
        % (' is-active' if t == ACTIVE else '', t)
        for t in items
    )
    html = '''<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { width: 220px; height: 660px; font-family: -apple-system, "PingFang SC",
  "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif; background: #f5f6f8; }
.sidebar { width: 220px; height: 660px; background: #1e293b; display: flex;
  flex-direction: column; overflow: hidden; }
.logo { height: 60px; display: flex; align-items: center; gap: 10px;
  padding: 0 20px; border-bottom: 1px solid rgba(255,255,255,.08); }
.logo-mark { width: 32px; height: 32px; border-radius: 9px; flex-shrink: 0;
  background: linear-gradient(135deg, #3b82f6, #6366f1); position: relative; }
.logo-mark::after { content: ''; position: absolute; left: 9px; top: 9px;
  width: 14px; height: 14px; border: 2.5px solid #fff; border-bottom: none;
  border-radius: 7px 7px 0 0; }
.logo-text { color: #fff; font-size: 15px; font-weight: 600; white-space: nowrap; }
.menu { flex: 1; padding: 6px 0; list-style: none; }
.menu-item { height: 50px; line-height: 50px; display: flex; align-items: center;
  padding: 0 20px; color: #94a3b8; font-size: 14px; border-left: 3px solid transparent;
  cursor: pointer; }
.menu-item .ico { width: 18px; height: 18px; margin-right: 10px; flex-shrink: 0;
  border: 1.6px solid currentColor; border-radius: 4px; opacity: .9; }
.menu-item.is-active { color: #fff; background: rgba(59,130,246,.18);
  border-left-color: #3b82f6; }
</style></head><body>
  <div class="sidebar">
    <div class="logo"><div class="logo-mark"></div><span class="logo-text">会议室预约系统</span></div>
    <ul class="menu">
      %s
    </ul>
  </div>
</body></html>''' % lis
    io.open('tools/_verify/admin-sidebar.html', 'w', encoding='utf-8').write(html)
    print('-> tools/_verify/admin-sidebar.html  菜单顺序:', ' / '.join(items))


if __name__ == '__main__':
    main()
