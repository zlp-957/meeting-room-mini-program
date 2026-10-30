-- ============================================================
-- 会议室扫码签到改造（2026-10-29）
--
-- 变更内容：
--   1. meeting_room 新增 sign_code（32位大写HEX签到码）/ sign_code_time，并建唯一索引
--   2. 回填存量会议室的签到码
--   3. booking 新增 (status, start_time) 复合索引，供「超时未签到」巡检使用
--   4. 历史数据处置：已通过、未签到、开始已超 15 分钟 → 置为已违约（不计入违约次数）
--
-- 【执行顺序，必须遵守】
--   停旧后端  →  执行本脚本  →  启动新后端
--   原因：旧版定时任务（每10分钟，判定条件 end_time < now）会继续把记录置违约
--         并累加 violation_count，与新脚本争抢同一批数据，导致违约次数被多算。
--
-- 本脚本可重复执行：加列/加索引均先查 information_schema（MySQL 8 不支持
-- ADD COLUMN IF NOT EXISTS）。第 4 步是幂等的 UPDATE（条件已不满足时影响 0 行）。
-- ============================================================
USE meeting_room;

-- 0. 核对数据库时钟（脚本里 NOW() 走会话时区，需与后端 JVM 时区一致）
SELECT NOW() AS db_now, @@session.time_zone AS session_tz, @@global.time_zone AS global_tz;

-- ------------------------------------------------------------
-- 1. meeting_room 加签到码列 + 唯一索引
--    sign_code 允许 NULL：MySQL 唯一索引允许多个 NULL，
--    这样「加列 → 回填」可以分两步走，回填过程中不会撞唯一冲突。
-- ------------------------------------------------------------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = 'meeting_room' AND TABLE_NAME = 'meeting_room'
               AND COLUMN_NAME = 'sign_code');
SET @sql := IF(@col = 0,
  'ALTER TABLE meeting_room
     ADD COLUMN sign_code VARCHAR(32) DEFAULT NULL COMMENT ''扫码签到码（32位大写HEX，全局唯一）'' AFTER remark,
     ADD COLUMN sign_code_time DATETIME DEFAULT NULL COMMENT ''签到码最近生成/重置时间'' AFTER sign_code,
     ADD UNIQUE KEY uk_sign_code (sign_code)',
  'SELECT ''sign_code 已存在，跳过'' AS msg');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 2. 回填存量会议室
--
--    【重要】不能用 UUID()！MySQL 的 UUID() 是 v1（时间 + MAC），
--    同一台机器连续生成时只有前 8 位递增变化、后 24 位完全相同，
--    可被轻易推算，作为签到令牌毫无安全性。
--    改用 MD5(RAND() + UUID() + id)，实际熵来自 RAND()，逐行求值。
--    id 参与拼接保证即使 RAND() 重复也不会撞唯一索引。
-- ------------------------------------------------------------
UPDATE meeting_room
SET sign_code = UPPER(MD5(CONCAT(RAND(), UUID(), id, NOW(6)))),
    sign_code_time = NOW()
WHERE sign_code IS NULL OR sign_code = '';

-- ------------------------------------------------------------
-- 3. booking 加 (status, start_time) 索引
--    违约巡检条件为 status=1 AND check_in_time IS NULL AND start_time < now-15min；
--    现有 idx_room_date(room_id, start_time, status) 与 idx_user_status(user_id, status)
--    都无法用 status 做前缀，每次巡检会退化为全表扫描。
-- ------------------------------------------------------------
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = 'meeting_room' AND TABLE_NAME = 'booking'
               AND INDEX_NAME = 'idx_status_start');
SET @sql := IF(@idx = 0,
  'ALTER TABLE booking ADD KEY idx_status_start (status, start_time)',
  'SELECT ''idx_status_start 已存在，跳过'' AS msg');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 4. 历史数据处置（唯一不可逆的一步，执行前请先备份）
--
--    新规则下「会议开始后 15 分钟仍未签到」即判违约。上线瞬间，
--    历史上所有 status=1、未签到、且开始时间已过 15 分钟的记录都会被
--    定时任务命中。若不预处理，会造成：
--      a) 一批用户违约次数突然 +N，直接触发封禁
--      b) 如果补发通知，瞬间产生通知洪水
--    所以这里直接把这些记录置为违约，但【不累加 violation_count、不补发通知】。
--    之后由定时任务从干净状态接管，只处理新产生的记录。
--
--    备份（取消注释后执行，事后可据此回答用户申诉）：
--    CREATE TABLE booking_sign_migrate_bak_20261029 AS
--    SELECT id, user_id, room_id, start_time, end_time, status
--    FROM booking
--    WHERE status = 1 AND check_in_time IS NULL AND deleted = 0
--      AND start_time < DATE_SUB(NOW(), INTERVAL 15 MINUTE);
-- ------------------------------------------------------------
UPDATE booking
SET status = 4, update_time = NOW()
WHERE status = 1
  AND check_in_time IS NULL
  AND deleted = 0
  AND start_time < DATE_SUB(NOW(), INTERVAL 15 MINUTE);

-- ------------------------------------------------------------
-- 5. 自检
-- ------------------------------------------------------------
-- 5.1 每个在用会议室都必须有签到码，且各不相同
SELECT COUNT(*) AS room_total,
       SUM(sign_code IS NULL OR sign_code = '') AS room_without_code,
       COUNT(DISTINCT sign_code) AS distinct_code
FROM meeting_room WHERE deleted = 0;

-- 5.2 各状态预约数量（status=4 的数量应等于第 4 步影响行数）
SELECT status, COUNT(*) AS cnt FROM booking WHERE deleted = 0 GROUP BY status;

-- 5.3 索引确认
SHOW INDEX FROM booking WHERE Key_name = 'idx_status_start';
SHOW INDEX FROM meeting_room WHERE Key_name = 'uk_sign_code';
