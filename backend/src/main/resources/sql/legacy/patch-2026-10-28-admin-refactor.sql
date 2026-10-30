-- ============================================================
-- 管理端重构补丁（2026-10-28）
-- 覆盖范围：
--   1) 配备设施功能下线：把 room_device_rel 里的设备名追加进 meeting_room.remark，再删两张表
--   2) 会议室「审核方式」字段删除：DROP meeting_room.need_approval
-- 前置条件：先停掉后端服务再执行（DDL 隐式提交，不可回滚）
-- 适用版本：MySQL 8.0
-- 幂等性：第 0、1 步可重复执行；第 2、3 步为 DROP，重跑会报错，重跑时请跳过
-- ============================================================
USE meeting_room;

-- ---------- 0. 执行前快照（同库备份表，便于回滚） ----------
CREATE TABLE IF NOT EXISTS bak_room_device_20261028 AS SELECT * FROM room_device;
CREATE TABLE IF NOT EXISTS bak_room_device_rel_20261028 AS SELECT * FROM room_device_rel;
CREATE TABLE IF NOT EXISTS bak_meeting_room_20261028 AS SELECT id, name, remark FROM meeting_room;

-- ---------- 1. 数据迁移：设备名追加到备注 ----------
-- 结果形如：小型会议室；配备：投影仪、白板
-- 原备注为空时只写「配备：…」；按 remark varchar(255) 截断，避免迁移时插入失败
UPDATE meeting_room r
JOIN (
    SELECT rel.room_id,
           CONCAT('配备：', GROUP_CONCAT(d.name ORDER BY d.sort, d.id SEPARATOR '、')) AS dev_names
    FROM room_device_rel rel
    JOIN room_device d ON d.id = rel.device_id
    WHERE d.deleted = 0
    GROUP BY rel.room_id
) t ON t.room_id = r.id
SET r.remark = LEFT(
        CASE
            WHEN r.remark IS NULL OR TRIM(r.remark) = '' THEN t.dev_names
            ELSE CONCAT(r.remark, '；', t.dev_names)
        END, 255)
WHERE r.remark IS NULL OR r.remark NOT LIKE '%配备：%';

-- 校验：每个有设备的会议室备注都应包含「配备：」
SELECT id, name, remark FROM meeting_room ORDER BY id;

-- ---------- 2. 删除设施相关表（先删关联表） ----------
DROP TABLE IF EXISTS room_device_rel;
DROP TABLE IF EXISTS room_device;

-- ---------- 3. 删除会议室「审核方式」字段 ----------
-- 注意：MySQL 8 的 DROP COLUMN 不支持 IF EXISTS，列已删除时重跑会报错，属于预期
ALTER TABLE meeting_room DROP COLUMN need_approval;

-- ---------- 4. 结果校验 ----------
SELECT id, name, capacity, remark, status FROM meeting_room WHERE deleted = 0;
SHOW COLUMNS FROM meeting_room;
SELECT table_name FROM information_schema.tables
 WHERE table_schema = 'meeting_room' AND table_name LIKE 'room_device%';

-- ============================================================
-- 回滚提示：
--   1) 恢复表结构与数据：用 mysqldump 备份文件或 bak_*_20261028 备份表
--      CREATE TABLE room_device AS SELECT * FROM bak_room_device_20261028;
--      CREATE TABLE room_device_rel AS SELECT * FROM bak_room_device_rel_20261028;
--      （新建的表会缺主键/唯一键，正式回滚请用 mysqldump 备份还原）
--   2) 补回字段：
--      ALTER TABLE meeting_room ADD COLUMN need_approval TINYINT DEFAULT 1
--        COMMENT '是否需审批 0否 1是' AFTER close_time;
-- ============================================================
