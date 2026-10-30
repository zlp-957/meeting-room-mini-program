# 短信验证码「真正能发出去」的落地指南

> **当前版本状态（2026-10-30）**：本项目已**移除短信验证**，手机号改为用户自行填写、
> 不做验证（详见 README「手机号绑定」）。本文保留，作为「将来真要做短信时」的参考，
> 相关实现（`SmsCodeStore` / `SmsSender` / Redis 实现 / 腾讯云网关）已归档在
> `_backup/sms-verification-20261030/`。

> 适用场景：将来要把「填写手机号」升级为「验证手机号」（或恢复手机号登录）时，按本文接真实短信通道。

## 一、先看结论

| 事实 | 说明 |
|------|------|
| 代码侧已就绪 | `SmsSender` 接口 + HTTP 网关实现 + 未配置时的明确报错 + prod 启动断言，见第四节 |
| **卡点不在代码，在资质** | 国内短信自 2023 年起实行**签名实名制报备**，个人主体拿不到可报备的签名 |
| 真实时间成本 | 资质审核 1~2 工作日 + 签名审核约 2 小时 + **运营商报备 7~10 工作日** + 模板审核 1~2 小时 |
| 真实费用 | 约 0.04 元/条，首次开通送 100 条（个人认证）/ 500 条（企业认证） |

**阿里云官方口径**（帮助中心「短信服务计费方式、使用流程及审核规则」）：

> 在当前的短信签名实名制要求下，**个人账号的自用资质无法通过签名实名制报备**，个人用户请使用短信认证产品或升级为企业认证账号。

**腾讯云官方口径**（「使用须知」+「常见签名驳回原因」）：

> 签名归属主体为个人时**无法进行国内短信运营商签名实名报备**……请提前准备企业证件和授权委托书用于申请**他用资质**，或将账号升级为企业认证。
> 签名类型**不能是「小程序」「公众号」「网站」「APP」**，不符合运营商最新要求，会被拦截。

也就是说：**个人主体 + 小程序名称做签名**这条路，两家现在都走不通。这不是配置问题，改代码绕不过去。

## 二、三条可选路径

### 路径 A：借用单位资质（最匹配本项目）

会议室预约本身就是「单位内部系统」，用学校/学院或实习公司的资质申请是名正言顺的。

| 步骤 | 操作 | 时间 |
|------|------|------|
| 1 | 腾讯云账号完成**企业实名认证**，或用个人账号申请**他用资质**（需对方营业执照 + 腾讯云标准格式授权委托书） | 1~2 工作日 |
| 2 | 申请签名：签名内容填单位简称，类型选「公司」 | 约 2 小时 |
| 3 | 等待**运营商实名报备**（控制台自动提交，未报备的签名会被运营商拦截） | **7~10 工作日** |
| 4 | 申请验证码模板，例如「您的验证码是{1}，5 分钟内有效，请勿泄露」 | 1~2 小时 |
| 5 | 创建子账号，取 `SecretId` / `SecretKey`，并只授予短信权限 | 即时 |

材料清单（腾讯云）：营业执照（或事业单位法人证书）、授权委托书（他用时需要，须盖公章）、签名归属方证明。

### 路径 B：先用邮箱验证码（个人立刻可用，推荐作为过渡）

如果短期拿不到单位资质，把「手机号 + 短信验证码」换成「邮箱 + 邮件验证码」，个人开发者用免费 SMTP（QQ 邮箱/163 邮箱开启 SMTP 授权码）当天就能真发、真收，且不涉及运营商报备。

- 改动面：`SmsSender` 换成 `MailSender`（接口同形），小程序端把手机号输入框换成邮箱，其余风控逻辑（次数限制、锁定、一人一号）完全复用。
- 代价：用户体验比短信略差，且「手机号」不再是账号标识——但对简历项目和校内演示完全够用。

### 路径 C：不接真实通道，保持 mock

本地/答辩演示时 `meeting.sms-mock=true`，验证码会直接回填到输入框（历史实现中，小程序已做自动填充；当前版本已移除该功能）。
简历与面试里如实写「预留 SmsSender 抽象 + 网关协议，接厂商 SDK 即可切换」，并讲清为什么没接（资质门槛）——这比硬接一个半成品更能体现工程判断。

## 三、走路径 A 时，你需要准备的东西

1. 腾讯云（或阿里云）账号，完成企业实名认证 / 他用资质
2. 短信签名（单位简称）
3. **验证码模板 ID** 与模板变量名（默认约定变量为 `{1}`，对应我们发送的 `code`）
4. 子账号的 `SecretId` / `SecretKey`
5. 短信应用的 `SdkAppId`（腾讯云）

## 四、代码侧现状与配置映射

后端已经把这层抽象好了，**不需要改业务代码**：

| 配置项 | 含义 | 对应控制台字段 |
|--------|------|----------------|
| `meeting.sms.store` | 验证码存哪（`memory` / `redis`） | — |
| `meeting.sms.gateway.url` | 网关地址（POST JSON `{phone, code, scene}`） | 你自建网关的地址 |
| `meeting.sms.gateway.token` | 网关鉴权头（发到 `Authorization`） | 自定 |
| `meeting.sms.gateway.success-value` | 网关响应中代表成功的 `code` 值 | 自定 |

厂商密钥（`SecretId` / `SecretKey` / `SdkAppId`）**只放在网关侧**，不进入业务服务。

设计意图：**厂商密钥不进入业务服务**。业务服务只知道「有个 HTTP 网关负责发短信」，密钥由网关持有；换厂商只改网关，不动后端。

### 最小网关示例

`tools/_verify/sms_gateway_stub.py` 是纯桩（只打印不发送）。要真发，把它换成调用厂商 SDK 的版本即可，腾讯云 Python 版核心就这几行：

```python
from tencentcloud.common import credential
from tencentcloud.sms.v20210111 import sms_client, models

cred = credential.Credential(SecretId, SecretKey)
client = sms_client.SmsClient(cred, "ap-guangzhou")
req = models.SendSmsRequest()
req.SmsSdkAppId = "1400000000"
req.SignName = "单位简称"
req.TemplateId = "1234567"
req.PhoneNumberSet = ["+86" + phone]
req.TemplateParamSet = [code]          # 对应模板里的 {1}
client.SendSms(req)
```

把它包在一个 `POST /send` 的 HTTP 服务里（接收 `{phone, code, scene}`，返回 `{"code":0}`），再把 `SMS_GATEWAY_URL` 指向它，后端就通了——`HttpSmsGatewaySender` 已按「HTTP 2xx + 响应 code=0」判定成功，失败会抛 500 而不是静默吞掉。

> 同理，阿里云用 `aliyun-python-sdk-dysmsapi` 的 `SendSms`；两家都能用官方 SDK 省掉自己算签名。

## 五、开通后怎么确认真的发得出去

```bash
# 1. 关掉 mock，指向网关
java -jar meeting-room-backend-1.0.0.jar --server.port=8081 \
  --spring.profiles.active=prod \
  --meeting.sms-mock=false \
  --meeting.sms.gateway.url=http://127.0.0.1:9100/send
# （prod 还需要 DB / WECHAT / CORS 环境变量，缺了会被 SafetyGuard 拦下）

# 2. 发一条
curl -s -X POST http://localhost:8081/api/auth/sms-code \
  -H "Content-Type: application/json" -d '{"phone":"你的手机号"}'

# 期望：返回 {"code":200,...,"expireSeconds":300}，且【不含 debugCode】
# 手机能收到短信；网关日志里有该次请求

# 3. 用收到的验证码登录
curl -s -X POST http://localhost:8081/api/auth/sms-login \
  -H "Content-Type: application/json" -d '{"phone":"你的手机号","code":"收到的码"}'
```

判断标准很简单：**响应里没有 `debugCode` 字段** 才说明走的是真实通道；有 `debugCode` 就是还在 mock 模式（本地开发专用，生产会被 `SafetyGuard` 拒绝启动）。

## 六、别忘了这两条风控（已实现，无需再配）

- 同一验证码最多输错 5 次 → 锁定 15 分钟（超过直接 `429`）
- 同一手机号 60 秒只能发一次；同一 IP 每小时最多 20 条
- 验证码 5 分钟过期，验证成功即作废（防重放）

真实通道开通后，这些限制比 mock 时期更重要——每条短信都是真金白银，被刷一次就是成本。
