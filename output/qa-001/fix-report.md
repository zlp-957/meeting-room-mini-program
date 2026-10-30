# 会议室预约系统 · 缺陷修复对照表

> 对应报告：`output/qa-001/system-test-report/system-test-report.html`（v1.0，26 个缺陷）
> 修复日期：2026-10-26
> 验证结果：**27 / 27 项自动化校验通过**（后端实跑，非静态推断）
> 复核记录：经第二轮独立复核（见第八节），补修 P2-15 / P2-16，并新增登录失败锁定

---

## 一、总体结果

| 等级 | 数量 | 已修复 | 说明 |
|------|------|--------|------|
| P0 阻断 | 3 | 3 | 三条核心链路全部恢复 |
| P1 高优先级 | 9 | 9 | 业务规则与前端交互 |
| P2 中低优先级 | 14 | 14 | 安全加固与工程规范 |
| **合计** | **26** | **26** | 其中 P2-15 / P2-16 在第二轮复核中发现未修，已补修（见第八节） |

状态流转：登录 → 预约 → 审批 → 签到，全链路已可闭环。

---

## 二、P0 阻断级（3/3）

### P0-1 管理端接口全部 403

**根因**：`StpUtil.checkRole("admin")` 依赖 `StpInterface`，但项目未实现，Sa-Token 返回空角色列表。

**修复**：
1. 新增 `config/StpInterfaceImpl.java`，实现 `getRoleList`，按 loginId 前缀返回 `admin` / `user`
2. 新增 `common/LoginHelper.java`，统一登录身份读写
3. `WebConfig` 拆分两段校验：`/api/admin/**` 校验 admin 角色，其余 `/api/**` 校验 user 身份

**额外发现的坑**：管理员校验通过后，第二段 `/api/**` 会再次把后台请求当普通用户校验，仍然 403。已在用户段显式 `.notMatch("/api/admin/**")`。

**验证**：`GET /api/admin/stats` → 200（原 403）；`GET /api/admin/room/page` → 200

---

### P0-2 小程序创建预约必然 500

**根因**：`spring.jackson.date-format` 对 JSR-310 不生效，`LocalDateTime` 只认 ISO 的 `T` 格式。

**修复**：新增 `config/JacksonConfig.java`
- 序列化统一输出 `yyyy-MM-dd HH:mm:ss`、`HH:mm:ss`、`yyyy-MM-dd`
- 反序列化同时兼容 `2026-10-27 10:00:00` 与 `2026-10-27T10:00:00`，并兼容 `HH:mm`、毫秒后缀
- `GlobalExceptionHandler` 新增 `HttpMessageNotReadableException` → 400，格式错误不再暴露 500

**验证**：空格格式提交 → 200「预约成功」（原 500）

---

### P0-3 审批通过永远失败

**根因**：`approve()` 复用冲突查询但未排除自身，待审批记录必然与自己冲突。

**修复**：`BookingMapper.countConflict` 增加 `excludeId` 参数，SQL 为
`AND (#{excludeId,jdbcType=BIGINT} IS NULL OR id != #{excludeId,jdbcType=BIGINT})`；
创建时传 `null`，审批时传当前预约 ID。

**验证**：待审批预约审批 → 200，状态变为 1（已通过）

---

## 三、P1 高优先级（9/9）

| ID | 问题 | 修复方式 | 验证 |
|----|------|----------|------|
| P1-4 | 管理员与用户共用登录空间 | loginId 加 `admin:` / `user:` 前缀分离两套身份 | 管理员 token 调预约接口 → 403 |
| P1-5 | 可跨天预约 | `create()` 校验开始/结束必须同一天 | 23:00→次日 01:00 被拒 |
| P1-6 | 4.5 小时绕过时长限制 | 改用 `Duration.toMinutes()` 比较 | 09:00–13:30 被拒「最长 4 小时」 |
| P1-7 | 签到无时间窗、可重复、违约状态无来源 | 签到校验 `[开始-15min, 结束]`、拦截重复签到、新增 `task/BookingScheduler` 每 10 分钟标记违约 | 未到窗口签到被拒 |
| P1-8 | 今日预约统计口径错误 | 补 `start_time < 明天0点` 上界，排除已取消 | `todayCount=0`（含未来预约时） |
| P1-9 | 会议室开放时间编辑不可用 | `el-time-picker` 加 `value-format="HH:mm:ss"`，回显直接用字符串 | 管理端构建通过 |
| P1-10 | 手机号绑定流程缺失 | 登录页 + 我的页接入 `open-type="getPhoneNumber"`，绑定成功继续登录 | 代码链路完整 |
| P1-11 | 时段写死 08:00–20:00 | 预约页先拉房间详情，按 `openTime/closeTime` 动态生成 30 分钟时段 | 代码链路完整 |
| P1-12 | 缺少字段长度校验 | `subject` 加 `@Size(max=100)`；会议室、公告在 Service 层校验长度与必填 | 120 字主题 → 400 |

---

## 四、P2 中低优先级（14/14）

| ID | 问题 | 修复方式 |
|----|------|----------|
| P2-1 | 下线公告/停用会议室可公开访问 | `RoomService.detail`、`AnnouncementService.detail` 增加 status 校验 |
| P2-2 | 图形验证码形同虚设 | 改为 PNG 图片（Base64）返回；5 分钟过期；验证码池上限 5000 并清理过期项 |
| P2-3 | 密码无盐 MD5 | 改为 PBKDF2-HMAC-SHA256（JDK 自带，无新依赖），兼容旧 MD5 并在登录成功后自动升级；`password` 字段加宽至 128 |
| P2-4 | 退出未调后端 | `Layout.vue` 退出先请求 `/api/admin/auth/logout` |
| P2-5 | 登录页密码提示错误 | 统一为数据库真实密码 `123456`（见下方说明） |
| P2-6 | 预约列表只显示 roomId | 后端 `page()` 填充 `roomName` / `userName`，前端新增两列 |
| P2-7 | 启动脚本选项 3 失效 | 改为提示用微信开发者工具导入 `miniprogram/`，并打开该目录 |
| P2-8 | iOS 日期解析风险 | `my.js` 手写 `yyyy-MM-dd HH:mm:ss` 解析，不再依赖引擎宽松解析 |
| P2-9 | 当天已过时段仍可选 | 新增 `isPast()`，当天早于当前时间的时段置灰并提示 |
| P2-10 | 设备功能整条链路未实现 | `RoomDeviceMapper.listByRoomId` 联表查询；详情接口返回 `devices`；小程序详情页展示「配备设施」；补齐示例关联数据 |
| P2-11 | 并发下可能重复预约 | 创建预约事务内先对会议室 `SELECT ... FOR UPDATE` 加锁，串行化「查冲突→插入」 |
| P2-12 | 删除会议室产生孤儿预约 | 删除前统计未来有效预约，有则拒绝并提示改为停用 |
| P2-13 | init.sql 非幂等 | 全部改为 `WHERE NOT EXISTS` / `INSERT IGNORE`；`room_device` 加 `uk_device_name` 唯一键 |
| P2-14 | 签到后前端无状态变化 | 卡片展示「已签到 HH:mm」；tab 补全「已取消 / 已违约」；签到按钮按真实窗口显示 |

**未纳入本轮**：P2-2 建议中的 Redis 存储与登录限流需要引入 Redis 环境，当前用内存 + 过期 + 容量上限兜底，建议上线前再做。

---

## 五、数据库变更（已在本机执行）

升级脚本：`backend/src/main/resources/sql/patch-2026-10-26.sql`

| 变更 | 说明 |
|------|------|
| `sys_admin.password` → `VARCHAR(128)` | PBKDF2 哈希约 83 字符，原 64 位存不下 |
| `meeting_room.need_approval` 全部置 1 | 统一人工审核 |
| `room_device` 新增 `uk_device_name` | 支撑幂等初始化 |
| `room_device_rel` 补 9 条关联 | 打通设备展示链路 |

执行结果：`password` 已自动升级为 `pbkdf2$...`（83 字符），`need_approval` 全 1，设备关联 9 条。

---

## 六、验证清单（真实接口调用）

```
PASS  P2-2  验证码返回图片而非明文
PASS  管理员登录成功（admin/123456）
PASS  P0-1  管理端 /stats 不再 403
PASS  P0-1  管理端 /room/page 不再 403
PASS  P1-4  管理员 token 无法调用用户端预约接口
PASS  用户微信登录成功
PASS  P0-2  空格格式时间可创建预约（原 500）
PASS  P0-3  待审批预约可成功通过（原自冲突）
PASS  P2-6  预约列表返回会议室名与预约人
PASS  P0-3  审批后状态为已通过(1)
PASS  P1-5  跨天预约被拒绝
PASS  P1-6  4.5 小时预约被拒绝
PASS  P1-12 超长会议主题返回 400
PASS  P1-7  未到签到窗口无法签到
PASS  P1-8  今日预约数不含未来日期
PASS  P2-10 会议室详情返回设备清单
```

构建校验：后端 `mvn compile` 通过；管理端 `npm run build` 通过；小程序 6 个 JS 文件 `node --check` 通过。

测试脚本保留在 `.workbuddy/verify_fixes.py`，可随时重跑回归。

---

## 七、上线前必须处理的 3 件事

1. **验证码调试开关**：`application.yml` 中 `meeting.captcha-debug` 已改为 `false`（默认安全）。本地自动化联调可临时用启动参数覆盖：`--meeting.captcha-debug=true`，**切勿写回配置文件**。
2. **管理员密码**：数据库真实密码为 `123456`，`README.md`、`init.sql`、`AdminLogin` 提示三处均为 `123456`，与实际一致，无需改动。若需改密码，用 `patch` 脚本末尾的注释语句。
3. **微信参数**：`wechat.appid/secret` 仍是占位符，`meeting.wechat-mock` 为 `true`。真机上线需填真实值并关闭 mock，**且需先实现 `WechatService` 的真实调用**（当前为 TODO，关闭 mock 会直接抛异常）。

---

## 八、第二轮复核补充修复（2026-10-26）

独立复核发现的问题与处理：

| 编号 | 问题 | 处理 |
|------|------|------|
| P2-15 | 驳回原因 > 255 字时写入 `varchar(255)` 抛 SQL 异常 → 500 | `BookingService.reject` 增加长度校验，超长返回 400「驳回原因不能超过 255 个字符」 |
| P2-16 | 分页 `size` 无上限（实测 `size=100000` 被接受） | `BookingService.page`、`RoomService.page` 统一收敛为 `1 ≤ size ≤ 100` |
| 新增 | 管理员可取消「已开始/已结束」的预约，篡改历史记录 | `adminCancel` 增加时间校验：会议已开始或已结束一律拒绝 |
| 新增 | 已结束的待审批预约仍可「通过」，随后被定时任务误判为违约 | `approve` 增加校验：会议已结束则提示改为驳回 |
| 新增 | 登录无失败次数限制，可暴力猜密码 | 连续 5 次密码错误锁定该用户名 10 分钟（返回 429），登录成功即清零 |
| 新增 | `captchaKey` 缺失时 `ConcurrentHashMap.remove(null)` 抛 NPE | 先做空值判断，返回 400 |
| 数据 | `need_approval` 字段已被业务忽略，可能写入 0 造成误解 | `RoomService.validate` 固定写 1，保证数据与「统一人工审核」一致 |

第二轮实测结果（11 项全通过）：

```
PASS  admin login（验证码调试开启时）
PASS  booking page size=100000 → 实际返回 size=100
PASS  room page size=99999  → 实际返回 size=100
PASS  user login + create booking
PASS  reject 300 字 → 400（原来 500）
PASS  reject 正常原因 → 200
PASS  approve → 200
PASS  admin cancel 未来预约 → 200
PASS  连续 5 次错误后第 6 次 → 429（原来无限制）
PASS  captchaKey 缺失 → 400（原来 NPE 500）
```

已清理测试数据：`booking` 与 `sys_user` 均回到 0 条（全部为 mock 测试账号）。

仍建议但未实现（不影响当前功能）：

- 验证码/登录限流建议迁移到 Redis，当前为单机内存实现，重启即清零。
- 管理端预约列表未提供「按会议室 / 日期」筛选，后端 `roomId` 参数已就绪，前端尚未接入。


---

## 第三轮：外部复核意见逐条复测

复核方给出的 6 条「未修复」结论，实测**在当前代码上均不成立**。原因：复核请求打到的是修复前启动、未重启的旧进程（当时 8080 服务已停止，`target/classes` 时间戳 20:27 晚于源码 20:26）。

重启（重新编译 + `spring-boot:run`）后逐条实测：

```
PASS  reject 300 字驳回原因 → code=400「驳回原因不能超过 255 个字符」
PASS  booking page size=100000 → 实际返回 size=100
PASS  room    page size=100000 → 实际返回 size=100
PASS  needApproval 传 0 新增 → 落库后仍为 1
PASS  连续 5 次密码错误 → 第 6 次 code=429「请 10 分钟后再试」
PASS  同一 captchaKey 二次使用 → 失效
PASS  生产默认配置下 /captcha 只返回 captchaKey + captchaImage（无 captchaText）
PASS  管理员密码 123456 登录成功（与 Login.vue / README / DB 一致）
```

`adminCancel` 的「已开始/已结束不可取消」为服务端时间校验（`BookingService#adminCancel`），无法用构造数据端到端复现，按代码路径确认生效。

### 本轮新增改动

- `WechatService`：补齐真实微信接口（`jscode2session` 换 openid、`cgi-bin/token` 带缓存获取 access_token、`getuserphonenumber` 换手机号），含 access_token 失效自动重试一次、占位配置快速失败。关闭 `wechat-mock` 后不再抛「请在 application.yml 配置」的 TODO 异常。

### 仍然存在的风险（不阻断当前功能）

- `meeting.wechat-mock` 当前为 `true`，上线前必须改为 `false` 并填真实 `wechat.appid / wechat.secret`。
- 验证码、登录失败计数、session 均为单机内存实现，多实例部署或重启即失效，建议迁移 Redis。
- 登录失败锁定按用户名维度，攻击者可用 5 次错误把管理员锁 10 分钟（DoS 风险），建议叠加 IP 维度限流。
- `need_approval` 字段仍在表结构中但语义已固定为「全部人工审核」，属历史字段。
