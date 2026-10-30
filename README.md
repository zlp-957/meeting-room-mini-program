# 会议室预约系统

企业内部会议室预约系统，采用「微信小程序（用户端）+ Web 管理后台（管理员端）」双端架构。

## 项目结构

```
├── backend/          # Spring Boot 后端服务
├── miniprogram/      # 用户端小程序（微信原生，直接用微信开发者工具打开）
├── admin-web/        # 管理端 Web（Vue3 + Element Plus + Vite）
├── docs/             # 设计说明与专题文档
├── test-report/      # 独立测试报告与逐项修复记录
├── tools/_verify/    # 不依赖外部服务的自检脚本（地址解析 / 绑定一致性 / 页面生命周期）
└── output/           # 需求分析与设计文档
```

## 环境要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 8 及以上（8 / 11 / 17 均可） | 后端运行 |
| Maven | 3.6 及以上 | 后端构建（也可用 IDE 内置 Maven） |
| MySQL | 8.0 | 数据库（5.7 未验证，脚本用到 8.0 的动态 SQL） |
| Node.js | 16 及以上 | 管理端构建与开发服务器 |
| 微信开发者工具 | 最新稳定版 | 运行小程序（基础库建议 2.20+） |

端口占用：后端 `8080`、管理端 `3000`。两个都可改（后端改 `server.port`，管理端改 `vite.config.js` 的 `server.port` 与代理目标）。

## 技术栈

| 端 | 技术 |
|----|------|
| 用户端 | 微信原生小程序（WXML / WXSS / JS），微信开发者工具直接打开 |
| 管理员端 | Vue3 + Vite + Element Plus + Pinia + Axios |
| 后端 | Spring Boot 2.7.x + MyBatis-Plus + MySQL 8.0 + Sa-Token |

## 快速开始

### 一键启动（推荐）

双击根目录的 **`start.bat`**，按菜单选择即可：

```
[1] 启动后端      (Spring Boot, 8080)
[2] 启动管理端    (Vue3 + Vite, 3000)
[3] 打开小程序    (微信原生, 微信开发者工具)
[4] 同时启动后端 + 管理端
[5] 退出
```

> 脚本全部使用英文提示，避免中文编码导致闪退。

### 1. 初始化数据库（只跑一个脚本）

```bash
mysql -u root -p < backend/src/main/resources/sql/init.sql
```

**只需要执行这一个脚本**，它同时适用于两种情况：

- **全新环境**：建库 + 建表 + 初始数据一次完成；
- **已有环境的升级**：脚本会检查缺哪些列 / 索引 / 表并补齐，可重复执行。

脚本末尾会输出 3 行自检结果，正常应为：

| 自检项 | 期望值 |
|--------|--------|
| `user_key_columns` | 6 |
| `room_without_code` | 0 |
| `notification_table` | 1 |

> `backend/src/main/resources/sql/legacy/` 里是历史补丁脚本，**默认不需要执行**。
> 只有在升级一个 2026-10-30 之前的极老库、或需要核查历史变更时才用得上，说明见该目录的 README.md。

默认管理员账号：`admin`，密码：`123456`（首次登录后自动升级为加盐哈希，**请立即修改密码**）

### 2. 配置本地参数

后端配置按 profile 拆分，仓库里**不含任何密码**：

| 文件 | 用途 | 是否入库 |
|------|------|----------|
| `application.yml` | 公共配置；所有敏感项从环境变量读取，默认值取安全侧（mock 全关） | 是 |
| `application-local.yml` | 本地开发：数据库密码 + mock 开关 | **否（.gitignore 已排除）** |
| `application-local.yml.example` | 本地配置模板 | 是 |
| `application-prod.yml` | 生产：数据库与微信凭据必须由环境变量注入 | 是 |

首次拉代码后（**这一步不能跳过**：默认 profile 是 `local`，缺这个文件会用空密码连库，并且微信登录会走真实接口而失败）：

```bash
cd backend/src/main/resources

# macOS / Linux / Git Bash
cp application-local.yml.example application-local.yml

# Windows CMD
copy application-local.yml.example application-local.yml
```

然后打开 `application-local.yml`，把 `password: 你的MySQL密码` 改成你本机 MySQL 的密码。
文件里已经带 `wechat-mock: true`，本地无需真实微信凭据即可登录。

生产启动示例（缺任意一项都会直接启动失败，不会带着空密码连库）：

```bash
DB_URL="jdbc:mysql://db:3306/meeting_room?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
DB_USERNAME=meeting_app DB_PASSWORD=****** \
WECHAT_APPID=wx**** WECHAT_SECRET=****** \
MEETING_CORS_ORIGINS=https://admin.example.com \
java -jar meeting-room-backend.jar --spring.profiles.active=prod
```

启动时会执行一次安全断言（`SafetyGuard`）：prod 下若发现 `wechat-mock=true`、`captcha-debug=true`、
微信 appid 仍是占位符、或数据库密码为空，服务直接启动失败并列出原因。

### 3. 启动后端

```bash
cd backend
start-backend.bat        # Windows 双击或命令行
# 或手动：mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`，接口文档见 `http://localhost:8080/doc.html`

### 4. 启动管理端

```bash
cd admin-web
start-admin.bat          # Windows 双击或命令行
# 或手动：npm install && npm run dev
```

管理端默认运行在 `http://localhost:3000`，已配置代理转发到后端 8080。

### 5. 运行小程序（用户端）

小程序是**微信原生**项目，直接用微信开发者工具打开即可，无需 HBuilderX：

1. 打开微信开发者工具，点「导入项目」，选择 `miniprogram` 目录
2. AppID 选择「测试号」（或填入你自己的 AppID）
3. 项目配置已关闭域名校验（`urlCheck: false`），本地 `localhost:8080` 可直接访问

**后端地址怎么改**：只改 `miniprogram/utils/env.js` 里的 `LAN_IP` / `PORT`。

- 仓库里的 `LAN_IP` 是**示例值 `192.168.1.100`**，用真机预览前必须改成你自己电脑的局域网 IP；
- 开发者工具里自动走 `localhost`，**不改也能直接跑**；
- 真机预览要换成电脑的局域网 IP（Windows `ipconfig` / macOS `ifconfig` 查 IPv4）；
- 换网络后不想改代码：登录页**长按 Logo**可直接输入新地址（存在本地缓存，优先级最高）——
  给别人演示时推荐用这个方式，不用改文件、不用重新编译。

**关于 AppID**：`miniprogram/project.config.json` 里当前是作者本人的 AppID，仅供识别项目结构。
你导入项目时填自己的 AppID 或选「测试号」即可；本地 `wechat-mock: true` 时不需要真实凭据也能登录。

## 核心功能

**用户端（小程序）**
- 微信一键登录
- 填写 / 修改手机号（只做格式与唯一性校验，不做短信验证）
- 会议室列表 / 详情、按时段预约（冲突检测）
- 我的预约（状态查看、取消、修改、扫码签到）
- 公告、站内通知

**管理员端（Web）**
- 账号密码登录（图形验证码 + 连续失败锁定）
- 数据概览
- 会议室管理（增删改查、签到码查看 / 重置）
- 预约管理（审批通过 / 驳回 / **代用户取消**、按主题 / 预约人 / 会议室 / 状态 / 签到状态筛选）
- 公告管理（分页）
- 用户管理（限时封禁 / 永久禁用 / 解封 / 解除禁用）

## 配置说明

### 业务规则（`meeting.*`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `max-advance-days` | 14 | 最多提前几天预约 |
| `max-duration-hours` | 4 | 单次预约最长时长 |
| `slot-minutes` | 15 | 预约粒度；会议室开放时间也必须是它的整数倍 |
| `gap-minutes` | 15 | 相邻两场预约的最小间隔 |
| `cancel-before-hours` | 2 | 用户取消 / 修改的提前量（管理员取消不受此限） |
| `sign-window-minutes` / `sign-late-minutes` | 15 / 15 | 签到窗口 = 开始前 N 分钟 ~ 开始后 M 分钟 |
| `violation-ban-threshold` / `violation-ban-days` | 3 / 30 | 连续违约 N 次禁止预约 M 天 |

### 安全开关（`meeting.*`）

| 配置项 | 默认 | 生产要求 |
|--------|------|----------|
| `wechat-mock` | false | 必须 false；true 时任何人可伪造 openid 登录任意账号 |
| `captcha-debug` | false | 必须 false；true 时图形验证码明文返回 |
| `cors.allowed-origins` | localhost:3000 等 | 生产必须改为实际管理端域名，不支持通配符 |

### 手机号绑定（不做短信验证）

本项目**不启用短信验证码**，手机号由用户自行填写，只做两件事：

- 格式校验（11 位、1 开头）
- 唯一性校验（`sys_user.uk_phone` 唯一索引 + 业务层友好提示）

**为什么可以不做验证**：手机号在这里只是资料字段与联系方式，**不作为登录凭据**（登录只走微信授权）。
因此「抢占他人手机号」的后果仅限于占住一个号码的展示位，无法借此登录别人的账号。

**红线**：不要在此基础上重新开启「手机号登录」。一旦手机号能登录，「谁先绑定谁拥有」就变成
完整的账号接管链路（绑他人号码 → 用该号码登录），必须先有验证码校验才允许。

> 将来若要接真实短信（例如部署到单位内部），完整门槛与步骤见 [docs/sms-开通指南.md](docs/sms-开通指南.md)，
> 历史实现（`SmsCodeStore` / `SmsSender` / Redis 实现 / 腾讯云网关）已归档在 `_backup/sms-verification-20261030/`，
> 恢复时把 java 目录下的文件放回对应包并加回 `spring-boot-starter-data-redis` 依赖即可。

### 自检脚本（不依赖外部服务，随时可跑）

```bash
node tools/_verify/verify_env_config.js     # 小程序后端地址解析的 7 种情形
node tools/_verify/verify_miniprogram.js    # 事件绑定 / 页面注册 / tabBar 图标一致性
node tools/_verify/simulate_create_page.js  # 无头跑一遍预约页 onLoad，断言日期条与空态
node tools/_verify/verify_avatar_display.js # 头像渲染链路（http 下载 → 本地文件 → image）
```

前三个是确定性检查：第一个断言完整 URL，第二个校验绑定方法与文件存在性，
第三个断言「日期卡 14 张 / 网络失败时仍生成默认时间格 / loadFailed 置位」。
第四个覆盖头像：http 必须落本地文件后再渲染、https 直连、缓存命中与失效、下载失败回退、重试只做一次。

> **头像为什么不直接用网络地址**：自基础库 3.8.7 起，`image` 组件在开发版/体验版
> 不再加载 HTTP 协议的图片（开发者工具能显示、真机静默失败，且「不校验合法域名」管不到它）。
> 本地后端是 `http://192.168.x.x:8080`，所以头像走三级降级：
> https 直接渲染 → http 用 `downloadFile` 落成本地文件 → 仍失败则用 `wx.request`
> 取字节转 `data:` URI（data 协议不走网络请求，接口能通就画得出来）。
> 生产环境用 https 域名时走第一级，没有额外请求。

### 其它

- `wechat.appid/secret`：小程序凭据，生产通过 `WECHAT_APPID` / `WECHAT_SECRET` 注入
- `wechat.subscribe-templates.*`：订阅消息模板 ID（`remind` / `violation` / `ban`），
  **必须在小程序后台申请**，无法预置；未填时 prod 启动打印告警，功能降级为「只落站内通知」。
  申请后只需填后端配置，**小程序端不用改**：模板 ID 由 `GET /api/subscribe/templates` 下发，
  小程序预约成功后用它调用 `wx.requestSubscribeMessage` 完成授权（返回空数组则静默跳过）。
  这一步不能省 —— 订阅消息是「一次授权 = 一次下发额度」，用户没授权过，后端下发会被微信拒绝（43101）。

## 上线检查清单

### A. 部署动作

1. **建库**：`mysql -u root -p < backend/src/main/resources/sql/init.sql`（可重复执行）
2. **起后端**（JDK 8+ / MySQL 8）：注入下表环境变量，显式 `--spring.profiles.active=prod`
3. **部署管理端**：`cd admin-web && npm run build`，把 `dist/` 交给 Nginx；
   `src/utils/request.js` 的 `baseURL` 为空即「同源请求」，所以推荐 **Nginx 同域反代**：

   ```nginx
   location / { root /var/www/meeting-admin; try_files $uri $uri/ /index.html; }
   location /api/ { proxy_pass http://127.0.0.1:8080; }
   location /upload/ { proxy_pass http://127.0.0.1:8080; }
   ```

   管理端与后端不同域时必须把管理端域名加进 `MEETING_CORS_ORIGINS`，否则浏览器会拦请求。
4. **小程序发版**：`miniprogram/utils/env.js` 填 `PROD_BASE_URL`（https）→ 微信后台
   「开发管理 → 开发设置 → 服务器域名」加同一域名为 request 合法域名 → 上传 → 体验版验收 → 提交审核 → 发布。
   **正式版用 http 或 IP 一定连不上**（微信强制 https + 域名白名单），这一步不做，正式版打开就是「网络连接失败」。
5. **打印签到二维码**：管理端会议室列表 → 签到码 → 重新生成并打印（room4 的码在测试中被重置过，旧码已失效）
6. **改管理员默认密码**：初始化账号 `admin / 123456`，首次登录后立刻改

### B. 只有账号持有者能完成的外部配置

| 项 | 在哪做 | 不做会怎样 |
|----|--------|-----------|
| 订阅消息模板 ID（remind / violation / ban） | 微信小程序后台「订阅消息」申请，回填 `wechat.subscribe-templates.*` | 只剩站内通知，微信侧不推送 |
| 微信 appid / secret | 小程序后台获取，填 `WECHAT_APPID` / `WECHAT_SECRET` | 微信登录失败，`SafetyGuard` 也会拒绝 prod 启动 |
| 已备案 https 域名 | 域名备案 + 证书 | 小程序无法发版，后端只能内网访问 |

### C. 已知限制（单实例部署可接受）

| 限制 | 影响 | 需要时怎么升级 |
|------|------|----------------|
| Sa-Token 会话存在应用内存 | 后端重启后所有用户需重新登录；多实例间不共享 | 引入 `sa-token-redis-jackson` 并配置 Redis 存储 |
| 头像文件落本地磁盘（`meeting.upload.dir`） | 换机器/扩容后旧头像 404 | 改成持久卷或对象存储，`FileStorageService` 内替换实现 |

## 接口约定

- 统一响应体：`{ code, msg, data }`
- `code` 语义：`200` 成功、`400` 参数或业务规则不满足、`401` 未登录、`403` 无权限、
  `404` 资源不存在、`409` 与当前状态冲突、`429` 频率限制、`500` 未预期的系统异常
- 业务拦截一律返回 4xx：`5xx` 只代表真实故障，便于监控告警

## License

[MIT](LICENSE)
