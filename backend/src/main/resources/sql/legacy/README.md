# 历史补丁脚本（归档，默认不需要执行）

`../init.sql` 已经吸收了这些脚本里仍然必需的全部结构变更，**全新部署或常规升级都只需要跑 init.sql**。
这里保留它们仅为了两种情况：

1. 需要核查某个历史变更当时到底改了什么（审计 / 排障）；
2. 手上有一个 **2026-10-30 之前** 的极老库（例如带 `need_approval`、`room_device` 的版本），
   想按当时的原始顺序升级。

## 各脚本作用与现状

| 脚本 | 历史作用 | 现在还需要吗 |
|------|----------|--------------|
| `patch-2026-10-26.sql` | 管理员密码列加宽到 128、`need_approval` 置 1、设备表唯一键与关联数据 | 不需要。`need_approval` 与设备表已下线，密码列长度 init.sql 已包含 |
| `patch-2026-10-28-admin-refactor.sql` | 设备名并入 `meeting_room.remark`、DROP 设备表、DROP `need_approval` | 仅当库结构还停留在 2026-10-28 之前时需要 |
| `patch-2026-10-28-booking-upgrade.sql` | `sys_user.violation_count/banned_until`、`booking.remind_time`、`notification` 表 | 不需要。已合并进 init.sql 第二节 |
| `patch-2026-10-28-phone-unique.sql` | `sys_user.phone` 普通索引改唯一索引 | 不需要。已合并进 init.sql 第二节 |
| `patch-2026-10-29-sign-qr.sql` | 签到码列与唯一索引、`idx_status_start`、历史数据处置 | 不需要。列与索引已合并；其中的历史数据处置属于一次性动作，新库没有存量数据 |
| `migrate_sms_login.sql` | `openid` 改可空、删 `uk_openid` | 不需要。init.sql 建表语句已是最终形态 |

## 执行顺序（仅老库升级场景）

```
停后端 → patch-2026-10-26.sql → patch-2026-10-28-admin-refactor.sql → patch-2026-10-28-booking-upgrade.sql
      → patch-2026-10-28-phone-unique.sql → patch-2026-10-29-sign-qr.sql → 再跑一次 init.sql（幂等，用于补齐新列）
```

> 顺序敏感的原因：`patch-2026-10-26.sql` 第 2、3 步依赖 `need_approval` 列与 `room_device` 表，
> 而这两个对象正好被 `patch-2026-10-28-admin-refactor.sql` 删除，反序执行必然报「Unknown column / Table doesn't exist」。
