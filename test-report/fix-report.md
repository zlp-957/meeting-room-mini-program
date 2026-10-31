# 测试报告问题修复说明

> 对应报告：《会议室预约小程序（重制版）· 系统测试报告》（2026-10-30，23 项问题：P0 2 / P1 6 / P2 15）
> 修复日期：2026-10-30
> 验证方式：Maven 全量编译打包 + 8081 独立实例真实接口回归 + 库结构二次执行校验（8080 正式实例未重启、未改动）

## 一、总体结论

| 项目 | 结果 |
|------|------|
| P0 | 2/2 已修复并实测 |
| P1 | 6/6 已修复并实测 |
| P2 | 13/15 已修复并实测；2 项受外部条件限制（见第四节） |
| 编译 | `mvn package` 通过；`npm run build` 通过（仅 chunk 体积告警） |
| 数据库 | `init.sql` 可重复执行，对存量库自动补齐新增列，已连跑两次验证 |

## 二、逐项修复明细

### P0

**P0-01 生产安全开关默认开启（任意手机号可接管账号）**

- `application.yml` 中三个开关默认值全部改为安全侧：`wechat-mock=false`、`sms-mock=false`、`captcha-debug=false`，且均支持环境变量覆盖。
- 配置按 profile 拆分：`application.yml`（公共，无密码）/ `application-local.yml`（本地开发，已被 `.gitignore` 排除，另附 `.example`）/ `application-prod.yml`（生产，凭据全部来自环境变量）。
- 新增 `config/SafetyGuard.java`：prod profile 下若发现任一 mock 开关为 true、微信 appid 仍是占位符、或数据库密码为空，**应用直接启动失败**并列出具体原因，不再依赖「记得改配置」。

> 实测：`--spring.profiles.active=prod --meeting.wechat-mock=true` 启动 → `BeanCreationException` / `IllegalStateException: [启动中止] prod profile 存在不安全配置…`，进程退出码 1。

**P0-02 按 README 建库会失败（初始化脚本不完整）**

- `init.sql` 重写为「建表 + 存量库补齐 + 初始化数据 + 自检」四段式，原先散落在 4 个补丁里的结构变更全部并入：
  `sys_user.violation_count / banned_until / ban_source / permanent_ban`、`booking.remind_time / cancel_by`、
  `notification` 表、`uk_phone`、`uk_sign_code`、`booking.idx_status_start`、签到码回填。
- 补齐逻辑走 `information_schema` 判断 + 动态 SQL，**MySQL 8 没有 `ADD COLUMN IF NOT EXISTS` 也能幂等**。
- 6 个历史补丁脚本归档到 `sql/legacy/`，并附 `legacy/README.md` 说明「默认不需要执行」及各自作用；README 改写为「只需执行 init.sql」+ 自检期望值。
- 未知列 / 缺表问题在脚本自检段直接暴露（`user_key_columns` / `room_without_code` / `notification_table` 三个输出）。

> 实测：在已有生产库上连续执行两次，均无报错，自检输出 `6 / 0 / 1`；`ban_source`、`permanent_ban`、`cancel_by` 三列成功添加。

### P1

| 编号 | 问题 | 修复方式 | 验证结果 |
|------|------|----------|----------|
| P1-01 | 占用时段接口泄露他人预约详情 | 新增 `BookingSlotVO`（仅 roomId/startTime/endTime/status），Mapper 改为 `SELECT room_id,start_time,end_time,status` 专用查询，从 SQL 层杜绝误加字段 | 返回体只剩 4 个字段，无 userId/subject/approverId/checkInTime |
| P1-02 | 封禁只拦「新建」不拦「修改」 | 抽出 `assertBookable(userId, now)`，`create` 与 `update` 共用 | 封禁后 `PUT /api/booking/update/{id}` 返回 400 封禁提示 |
| P1-03 | 手机号绑定无任何验证 | `BindPhoneDTO` 增加 `code`，服务端强制校验短信验证码（`meeting.sms.require-code-on-bind`，生产必须 true）；小程序「我的」绑定弹窗新增「获取验证码」+ 倒计时 | 无 code → 400「请先获取并填写短信验证码」；错码 → 400「还可尝试 4 次」；正确 → 绑定成功 |
| P1-04 | 管理员手动封禁被描述为「连续违约」 | 新增 `sys_user.ban_source`（1 违约自动 / 2 管理员人工）+ `common/BanSource`，提示按来源分流；到期自动解封时一并清空 | 人工封禁后返回「您的预约权限已被管理员暂停，恢复时间：…，如有疑问请联系管理员」 |
| P1-05 | 永久禁用可被「限时封禁/解封」部分解除 | 新增独立标记 `permanent_ban`；`ban` 遇永久禁用直接拒绝降级，`unban` 遇永久禁用拒绝并提示走新接口 `/api/admin/user/enable/{id}`；管理端列表按状态显示「解除禁用 / 解封 / 封禁」三个互斥按钮 | 永久禁用后设 1 天封禁 → 400；点解封 → 400；`enable` → 成功，库中 `status=1, permanent_ban=0` |
| P1-06 | 业务异常大量返回 code=500 | `BusinessException` 默认 code 改为 400；按语义使用 404（不存在）/409（状态冲突）/429（频率）；全局异常处理器区分日志级别（4xx 走 info，5xx 才 error） | 时段冲突、粒度不符、封禁等均返回 400；日志中 `ERROR` 计数为 0 |

### P2

| 编号 | 问题 | 修复方式 | 状态 |
|------|------|----------|------|
| P2-01 | 更新不存在的会议室/公告静默成功 | `RoomService.update`、`AnnouncementService.update/delete` 先判存在性，返回 404 | ✅ 实测 404 |
| P2-02 | 公告内容无长度校验 | 限制 20000 字符（TEXT 实际约 1.6 万汉字），前端同步加校验规则 | ✅ 20001 字符 → 400 |
| P2-03 | 非法参数返回 500 | 新增 `MethodArgumentTypeMismatchException` / `MissingServletRequestParameterException` / `ConstraintViolationException` 三个处理器 | ✅ `date=bad-date` → 400「日期格式不正确」 |
| P2-04 | 开放时间未校验 15 分钟对齐 | `RoomService.validate` 校验 open/close 时间必须是 `slot-minutes` 整数倍 | ✅ 08:07 → 400 |
| P2-05 | 验证码无失败次数与 IP 限流 | 同一验证码最多错 5 次即锁定 15 分钟并作废；同 IP 每小时最多 20 条；新增 `common/ClientIp` 解析 `X-Forwarded-For` | ✅ 第 5 次错 → 429 锁定；重发 → 429「请 55 秒后再试」 |
| P2-06 | 验证码/失败计数全在内存 | 抽出 `service/sms/SmsCodeStore` 接口 + `InMemorySmsCodeStore` 默认实现 + `SmsCodeStoreConfig` 装配。提供 Redis 实现 Bean 即自动替换，业务代码零改动 | ⚠️ 见第四节 |
| P2-07 | CORS 配置过宽 | 改为 `meeting.cors.allowed-origins` 白名单（默认仅本地 3000/8081），未配置则不开放跨域；`allowedOrigins` 精确匹配替代 `*` | ✅ 白名单来源 200 带 ACAO；陌生来源 403 无 ACAO |
| P2-08 | 仓库内明文数据库密码 / 登录页展示默认账号 | 密码移入被 gitignore 的 `application-local.yml`，仓库内仅留模板；登录页删除「默认账号 admin/123456」提示 | ✅ |
| P2-09 | 管理端用户接口暴露 openid | 新增 `UserListVO`，列表接口不再返回 openid | ✅ 分页响应无 openid 字段 |
| P2-10 | 管理端没有取消预约能力 | 新增 `POST /api/admin/booking/cancel/{id}`（带原因）+ `booking.cancel_by` 留痕 + 站内通知（type=4）；管理端「取消预约」按钮由后端下发 `canCancelByAdmin` 控制 | ✅ 取消成功，`cancel_by=1`，用户收到类型 4 通知 |
| P2-11 | 短信登录后端已实现但前端无入口 | 小程序登录页新增「使用手机号验证码登录」面板（获取验证码 + 倒计时 + 登录） | ✅ 代码就绪 |
| P2-12 | 订阅消息模板为空 | prod 启动时 `SafetyGuard` 对缺失模板打印告警；README 说明申请路径 | ⚠️ 见第四节 |
| P2-13 | 公告列表无分页 | 后端改 `/api/admin/announcement/page`，前端加分页组件 | ✅ 分页响应正常 |
| P2-14 | 管理端预约列表缺少筛选 | `page` 新增 `keyword`（会议主题 / 预约人昵称 / 手机号，LIKE 通配符已转义）+ 前端关键字、会议室、重置按钮；条件变化回到第 1 页 | ✅ 主题命中 1 条、昵称命中 5 条 |
| P2-15 | 小程序后端地址硬编码 | 地址常量抽到 `miniprogram/utils/env.js`，README 说明三种改法（改文件 / 长按 Logo / 工具默认 localhost） | ✅ |

## 三、顺带修掉的关联问题

这些问题报告里没单列，但修复上面各项时暴露出同类风险，一并处理：

1. **管理端 403 被当成掉线**：`request.js` 原来把 403 也当登录失效踢回登录页，管理员一遇权限提示就被登出。现在只有 401 才登出。
2. **LIKE 通配符未转义**：用户搜索框输入 `%` 会把全表捞出来，用户与预约的关键字筛选都已转义。
3. **用户详情/封禁状态口径不一致**：`UserDetailVO` 补充 `banSource / permanentBan`，前端不再用 `status` 反推封禁类型。
4. **日志中的完整手机号**：验证码锁定日志改为脱敏（`139****1111`）。
5. **`assertBookable` 的永久禁用兜底**：永久禁用用户即使持有未过期 token 也无法预约。

## 四、未完全解决 / 需要确认的项

> 第二轮补充（复核报告之后）：下表中「验证码存储」「真实短信通道」两项已闭环，见第七节；
> 目前只剩「订阅消息模板 ID」一项受外部账号限制。

| 项 | 现状 | 需要做什么 |
|----|------|-----------|
| P2-06 验证码存储 | ✅ 已闭环：`SmsCodeStore` 接口 + 内存实现 + **Redis 实现**，`meeting.sms.store=redis` 切换；失败计数改 `INCR` 原子自增；锁独立 key（可长于验证码有效期） | 多实例部署时设 `meeting.sms.store=redis` 并注入 `REDIS_HOST/PORT/PASSWORD` |
| 真实短信通道 | ✅ 已闭环：`SmsSender` 接口 + `HttpSmsGatewaySender`（HTTP 网关，POST JSON）+ 未配置时的明确报错实现；prod 下「关掉 mock 却没配网关」由 `SafetyGuard` 拒绝启动 | 注入 `SMS_GATEWAY_URL` / `SMS_GATEWAY_TOKEN`；接阿里云/腾讯云 SDK 时实现 `SmsSender` 即可替换 |
| P2-12 订阅消息模板 | 代码链路已闭环（后端下发模板 ID + 小程序授权 + 后端下发消息），**仅剩模板 ID 需在小程序后台真实申请** | 申请三个模板后填入 `wechat.subscribe-templates.*`（或 `WECHAT_TPL_*`），小程序端无需改动 |

## 五、部署与操作提醒

1. **先跑脚本再启服务**：`mysql -u root -p < backend/src/main/resources/sql/init.sql`（可重复执行）。本次新增了 `sys_user.ban_source`、`sys_user.permanent_ban`、`booking.cancel_by` 三列，旧后端仍可运行（新列均可空/有默认值）。
2. **本地启动前先建本地配置**：`cp application-local.yml.example application-local.yml` 并填本机 MySQL 密码（仓库内已不含密码）。
3. **生产启动必须带环境变量**：`DB_URL / DB_USERNAME / DB_PASSWORD / WECHAT_APPID / WECHAT_SECRET / MEETING_CORS_ORIGINS`，缺失或 mock 未关会直接启动失败。
4. **小程序绑定手机号现在必须输入短信验证码**，本地测试时 `sms-mock=true` 会把验证码自动回填到输入框。
5. **管理端预约页新增两个入口**：「取消预约」（兜底释放时段）、「解除禁用」（与「解封」区分）。用户页的「解封」只解限时封禁。

## 六、回归验证记录（摘要）

测试实例：`java -jar meeting-room-backend-1.0.0.jar --server.port=8081`（local profile，测试后已停止，8080 未动）

```
微信 mock 登录            → 200，返回 token
可用时段 GET available     → 仅 roomId/startTime/endTime/status
date=bad-date             → 400 日期格式不正确
未登录访问                 → 401
绑定手机号(无 code)        → 400 请先获取并填写短信验证码
绑定手机号(错 code)        → 400 还可尝试 4 次
绑定手机号(对 code)        → 200 绑定成功
验证码连错 5 次            → 429 请 15 分钟后重新获取
60 秒内重复发送            → 429 请 55 秒后再试
封禁后新建 / 修改预约       → 均 400「管理员已暂停…」
永久禁用后设 1 天封禁       → 400 不能降级为限时封禁
永久禁用后点解封           → 400 请使用「解除禁用」
解除禁用                   → 200，库中 status=1 permanent_ban=0
管理员取消预约             → 200，cancel_by=1，用户收到 type=4 通知
改/删不存在的会议室、公告    → 404
公告 20001 字符            → 400 不能超过 20000 个字符
开放时间 08:07             → 400 必须是 15 分钟整数倍
预约关键字(主题/昵称)       → 命中 1 条 / 5 条
CORS 白名单/陌生来源        → 200 带 ACAO / 403 无 ACAO
prod + mock=true 启动      → 启动失败（SafetyGuard 拦截）
init.sql 连续执行两次       → 均成功，自检 6/0/1
```

测试产生的临时数据（用户 1 个、预约 2 条、通知 1 条）已清理，`booking` 仅保留原有 33~36 号记录。

## 七、第二轮补充（复核报告之后）

Trae 复核报告给出 21/23 已确认修复，列出 3 项「上线前待办」。其中两项这轮做完了，并用本地桩服务做了端到端验证。

### 7.1 P2-06 验证码存储：真正可切 Redis

- 存储接口改为「意图级」操作，不再返回可变对象：
  `save / get / remove / increaseFailure / clearFailure / lock / lockedRemainMillis / size`。
  早期写法（取出对象改完再存回）在并发下会丢计数，等于把 5 次上限放大，
  这是暴力破解防护最容易失效的点。
- 失败计数用 `INCR` 原子自增；锁定标记用独立 key（`meeting:sms:lock:{phone}`），
  于是锁定时长可以长于验证码有效期 —— 否则验证码一过期锁就跟着失效，重发一条码即可继续爆破。
- 装配用 `@ConditionalOnProperty(meeting.sms.store=redis)` 选择实现，两个 Bean 都放在配置类里
  且 Redis 的声明在前（`@ConditionalOnMissingBean` 只在配置类里按声明顺序可靠）。

### 7.2 真实短信通道：从「抛异常占位」改为可配置网关

- 新增 `SmsSender` 接口 + `HttpSmsGatewaySender`（POST JSON `{phone, code, scene}`，
  可带 `Authorization`，按响应 `code` 判定业务成功）+ `UnconfiguredSmsSender`（未配置时明确报错，不静默吞）。
- `SafetyGuard` 增加一条 prod 断言：`sms-mock=false` 但网关 url 为空 → **拒绝启动**。
  原先是「上线后用户点获取验证码才发现 500」，现在启动即暴露。
- 没有内置某家云厂商 SDK：签名算法无法在无密钥环境下验证，交一段没跑过的代码没有意义；
  给出的是可用本地桩完整验证过的通用网关通道，接云厂商时实现 `SmsSender` 即可。

### 7.3 本轮实测（本地桩，未依赖任何外部服务）

工具在 `tools/_verify/`：`mini_redis.py`（最小 RESP 桩，支持 SETEX/GET/DEL/INCR/PTTL）、
`sms_gateway_stub.py`（短信网关桩，打印收到的验证码）。

```
启动：8083 + --meeting.sms.store=redis + --meeting.sms-mock=false + 网关指向 8099
POST /api/auth/sms-code        → 200，响应无 debugCode（只有 expireSeconds=300）
  短信桩日志                    → body={"phone":"13900003333","code":"442718"}，auth=verify-token
  Redis 桩日志                  → SETEX meeting:sms:code:13900003333 (ttl=300000ms)
POST /api/auth/sms-login(正确码) → 200，拿到 token
  Redis 桩日志                  → DEL meeting:sms:code:... / DEL meeting:sms:fail:...
60 秒内重发                    → 429「请 60 秒后再试」（sendAt 来自 Redis）
连续输错 5 次                  → INCR 1→5，第 5 次写入 SETEX meeting:sms:lock:... (ttl=900000ms)，返回 429
prod + sms-mock=false + 无网关  → 启动失败，提示注入 SMS_GATEWAY_URL
默认 memory 模式回归            → 发码带 debugCode、错码计数、正确码登录均正常（接口重构后无回归）
```

### 7.4 与复核报告的差异说明

- 复核报告写「P2-09 新增 UserListVO / UserDetailVO，列表不再返回 openid」：
  实际 `UserDetailVO` 是既有类（本次补了 `banSource / permanentBan` 两个字段），
  `UserListVO` 是本次新增、用于列表接口。结论（不再返回 openid）正确。
- 复核报告「未重跑项」（永久禁用/解封、管理员取消、prod 启动失败、init.sql 幂等、CORS 白名单）
  在本次修复时都已实跑过，记录见第六节，可复现。

### 7.5 P2-12 订阅消息：把「前端从未授权」这半个链路补上

复核报告把 P2-12 归为「等外部账号」，但代码侧其实还缺一段，不补的话即使模板 ID 填好也发不出去：

- 小程序 `create.js` 里早有 `requestSubscribe()`，但它读的 `this.data.subscribeTemplates`
  **从来没被赋值过**（页面 data 里是空数组），等于这段代码永远走 `return`。
- 订阅消息的机制是「一次授权 = 一次下发额度」，用户没在小程序点过授权，
  后端下发时微信直接返回 43101，日志里只有一行 warn，表现为「配置都填了但收不到提醒」。

补法（单一数据源，前端不再维护第二份模板 ID）：

1. 后端新增 `GET /api/subscribe/templates` → `{tmplIds:[...], missingScenes:[...]}`，
   只返回已配置的 ID（去重去空），需要登录；
2. 小程序 `api.js` 加 `subscribeTemplates()`，`create.js` 的 `onLoad` 拉一次写入 data；
3. 预约成功后 `requestSubscribe()` 用它调 `wx.requestSubscribeMessage`；
   模板未配置时返回空数组 → 静默跳过，不打扰用户。

实测（8086 / 8087 两个实例）：

```
配置 remind + violation 两个模板 → {"tmplIds":["TMPL_REMIND_TEST","TMPL_VIOLATION_TEST"],"missingScenes":["ban"]}
完全未配置                     → {"tmplIds":[],"missingScenes":["remind","violation","ban"]}（小程序据此跳过授权）
未登录访问                     → 401
```

结论：申请到模板后只需改后端配置，小程序端零改动即可生效。

### 7.6 上线阻塞：小程序正式版拿不到后端地址

复核报告已确认 23/23 代码闭环，但「带上线」还差一处代码级阻塞，属于测试报告没覆盖的部署维度：

- 原 `utils/config.js` 只有两条分支：开发者工具 → `localhost`、其它 → 局域网 IP。
  于是**正式版（release）会去连 `192.168.x.x:8080`**，用户用 4G 一打开就是「网络连接失败」。
- 微信正式版强制 https + 域名白名单，http/IP 一律不通，所以这不只是「换个 IP」的问题。

改法：`config.js` 改为四分支，`utils/env.js` 新增 `PROD_BASE_URL`：

| 运行环境 | 取址 |
|----------|------|
| 登录页手填（本地缓存） | 手填值，优先级最高 |
| 正式版 release | `PROD_BASE_URL`（必须 https，非示例域名） |
| 开发者工具 | `http://localhost:8080` |
| 真机预览 / 调试 | `LAN_IP` |

正式版未配域名时不静默降级，而是回落局域网并打 `console.warn`，避免「以为配好了其实没有」。

用例覆盖 7 种情形（`node tools/_verify/verify_env_config.js`，全部 PASS）：
开发者工具、真机预览、正式版未配域名、正式版配 https、正式版配 http（视为未配）、
正式版仍是 example.com（视为未配）、本地缓存优先。

## 八、产品决策调整：下线短信验证码（2026-10-30 追加）

前文 P1-03 的修复方式是「绑定手机号必须校验短信验证码」。项目定位调整为**简历项目**后，
按你的决定改为：**手机号由用户自行填写，不做验证**。

### 8.1 改动内容

| 层面 | 变化 |
|------|------|
| 后端 | 删除 `BindPhoneDTO.code`、`AuthService` 的发码/校验/IP 限流、`/api/auth/sms-code`、`/api/auth/sms-login`；`bind-phone` 只校验格式与唯一性（重复绑定返回 409） |
| 依赖与配置 | 移除 `spring-boot-starter-data-redis`（jar 48MB → 38MB）、`meeting.sms.*`、`spring.redis.*`、`SafetyGuard` 的短信断言 |
| 小程序 | 「我的」绑定弹窗去掉验证码与倒计时，改为一行提示「手机号仅用于展示与联系，不作为登录方式」；登录页移除手机号验证码登录面板 |
| 归档 | `SmsCodeStore` / `SmsSender` / Redis 实现 / 腾讯云网关 / `ClientIp` / 两个 DTO 移入 `_backup/sms-verification-20261030/`，附恢复步骤 |

### 8.2 安全性如何守住

去掉验证码之所以成立，前提是**手机号不再是登录凭据**——这也是同时下线「手机号登录」的原因：

- 手机号只作资料与联系方式，抢占它的后果仅限占住一个号码的展示位，无法借此登录他人账号；
- 登录唯一入口是微信授权（`openid` 由微信侧签发）；
- 唯一性仍由 `sys_user.uk_phone` 唯一索引 + 业务层 409 兜住，避免同号多账号造成认领歧义；
- README 与 `BindPhoneDTO` 注释都写明了红线：**不要在没有验证码的前提下恢复手机号登录**。

### 8.3 实测

```
POST /api/auth/bind-phone  {"phone":"13900008888"}            → 200 绑定成功
另一账号绑同一号码                                             → 409「该手机号已绑定其他微信账号」
POST /api/auth/sms-code / sms-login                           → 404（端点已下线）
mvn package                                                    → 通过，jar 38MB（Redis 依赖已移除）
verify_miniprogram.js                                          → PASS（9 页 / 40 个绑定，比之前少 9 个短信相关绑定）
node --check（api/mine/login/create/env/config）                → 全部通过
```

顺带修掉一个回归：批量删代码时误删了 `mine.js` 的 `closeBindDialog`，
被 `verify_miniprogram.js` 当场抓到（「绑定了 closeBindDialog 但 js 中没有该方法」），已补回。

## 九、真机反馈：头像上传成功但界面不变（2026-10-30 追加）

### 9.1 先排除后端，再定位前端

真机上「选图 → 上传 → 提示成功 → 头像没变」，先查整条链路的后端侧，结论是**后端完全正常**：

```
upload/avatar/202610/cd22...jpg      存在，34721 字节，mtime 就是用户操作的时间
sys_user.avatar                      /upload/avatar/202610/cd22...jpg  ← 已更新
GET /upload/avatar/.../cd22...jpg    200，Content-Type: image/jpeg，34721 字节
```

也就是说：文件落盘了、数据库写入了、静态资源能访问。问题出在**小程序渲染**。

### 9.2 根因：image 组件不再加载 HTTP 图片

微信官方社区口径（基础库 3.8.7 起）：

> image 组件在开发版和体验版不再支持加载 HTTP 协议的图片。

本项目 `libVersion` 是 3.17.5，本地后端地址是 `http://192.168.x.x:8080`，于是：

1. `<image src="http://...">` 被拦 → 触发 `binderror`；
2. 原来的 `onAvatarError()` 直接 `setData({ avatarUrl: '' })`；
3. 头像立刻回退成文字头像 → 用户看到「传了半天没变化」。

注意这跟「不校验合法域名」是两回事：那个开关管的是 request/downloadFile 的域名白名单，
管不到 image 的协议限制，所以开发者工具正常、真机失败。

### 9.3 改法：让头像落到本地文件再渲染

`miniprogram/pages/mine/mine.js`：

| 位置 | 变化 |
|------|------|
| `resolveAvatarSrc` | 三级降级：https 直接渲染 → http 走 `downloadFile` + `saveFile` 成本地文件 → 仍失败则 `wx.request` 取字节转 `data:` URI（data 协议不走网络，接口通就能画） |
| `refreshAvatar` | 带递增序号防竞态（连续换头像时先发后到的请求不会盖回旧图） |
| `showPickedAvatar` | 上传成功后立刻用**刚选中的本地临时图**渲染，不等网络：用户马上看到变化 |
| `onAvatarError` | 不再直接清空；丢掉缓存重取一次，按头像路径记账，同一张图只重试一次（防死循环），仍失败才回退文字 |
| `forgetAvatarCache` | 换头像后删掉上一张的本地缓存文件，避免越积越多 |
| `applyUserProfile` | 只更新昵称/手机号等文字资料，不用网络地址覆盖刚渲染好的本地图 |

缓存 key 是 `avatarFile:{头像相对路径}`（路径含 UUID），所以换头像天然不会命中旧缓存。

### 9.4 顺带修掉一个全局时间戳 bug

排查时发现：头像改了，`sys_user.update_time` 还停在 10-28。原因是 `MybatisPlusConfig` 用了
`strictUpdateFill`，它的语义是「**字段为 null 才填充**」，而更新场景的实体是从库里查出来的
（updateTime 已有旧值），于是 `update_time` 永远不会变 —— 影响所有 `updateById` 的实体。

改为 `setFieldValByName` 无条件覆盖。实测：

```
上传头像 → avatar 变新文件，update_time 17:15:01（create_time 17:14:34）
改昵称   → update_time 17:15:10
```

### 9.5 验证

新增 `tools/_verify/verify_avatar_display.js`（假 wx 对象跑真实页面代码，17 项断言全 PASS）：

```
1) HTTP 头像        → 发起 1 次下载，渲染 wxfile:// 本地路径，写缓存
2) HTTPS 头像       → 不下载，直接渲染
3) 缓存命中         → 不下载，直接用缓存路径
4) 缓存文件被清理   → 重新下载
5a) 下载被拦        → 退到 wx.request(ArrayBuffer) → data:image/jpeg;base64 渲染，独立缓存
5b) 两条路都不通    → 不抛异常，回退文字头像
6) image 报错       → 首次清缓存重试，第二次不再重试，最终回退
7) 上传后即时渲染   → 立刻显示刚选的本地图，并写入缓存
```

后端侧实测（8090 独立实例）：`POST /api/user/avatar` 返回 `{url, userInfo}`，
文件落盘、URL 200、`update_time` 刷新；测试用户与文件已清理，8080 未重启。
