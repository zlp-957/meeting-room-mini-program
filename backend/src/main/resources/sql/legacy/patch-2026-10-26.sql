-- ============================================================
-- 存量数据库升级脚本（2026-10-26）
-- 适用场景：数据库已按旧版 init.sql 初始化过，需要同步本次修复带来的结构变化
-- 执行一次即可；脚本中的 ALTER 若报「已存在」可忽略
-- 适用范围：仅适用于 2026-10-28 之前的库结构。
--   执行过 patch-2026-10-28-admin-refactor.sql 后，need_approval 列与 room_device 表已被删除，
--   本脚本第 2、3 步会报错，请直接跳过。
-- ============================================================
USE meeting_room;

-- 1. P2-3 密码哈希升级
--    PBKDF2 哈希形如 pbkdf2$120000$salt$hash，长度约 83 字符，原 VARCHAR(64) 存不下
ALTER TABLE sys_admin MODIFY COLUMN password VARCHAR(128) NOT NULL COMMENT '密码（PBKDF2，兼容历史MD5）';

-- 2. 全部会议室统一为人工审核
UPDATE meeting_room SET need_approval = 1 WHERE need_approval <> 1;

-- 3. 设备表补唯一键（init.sql 的 INSERT IGNORE 依赖它做幂等）
--    若已存在同名索引会提示 Duplicate key name，忽略即可
ALTER TABLE room_device ADD UNIQUE KEY uk_device_name (name);

-- 4. 补设备与会议室的关联（设备链路由此打通，详情页会展示「配备设施」）
INSERT IGNORE INTO room_device_rel (room_id, device_id)
SELECT r.id, d.id FROM meeting_room r, room_device d
WHERE r.name = '第一会议室' AND d.name IN ('投影仪', '白板') AND r.deleted = 0;

INSERT IGNORE INTO room_device_rel (room_id, device_id)
SELECT r.id, d.id FROM meeting_room r, room_device d
WHERE r.name = '第二会议室' AND d.name IN ('投影仪', '白板', '视频会议') AND r.deleted = 0;

INSERT IGNORE INTO room_device_rel (room_id, device_id)
SELECT r.id, d.id FROM meeting_room r, room_device d
WHERE r.name = '多功能厅' AND d.name IN ('投影仪', '视频会议', '音响', '电视') AND r.deleted = 0;

-- 5. 【可选】忘记管理员密码时才执行，会把密码重置为 123456
-- UPDATE sys_admin SET password = MD5('123456') WHERE username = 'admin';

-- 校验结果
SELECT id, username, LENGTH(password) AS pwd_len, status FROM sys_admin;
SELECT id, name, capacity, need_approval, status FROM meeting_room WHERE deleted = 0;
