-- ============================================================
-- 会议室预约系统 · 数据库初始化脚本
--
-- 【一次执行可同时完成两件事】
--   1) 全新环境安装（建库 + 建表 + 初始数据）
--   2) 存量库结构升级（补齐历史补丁新增的列 / 索引 / 表）
--
-- 设计要点：
--   · 全脚本幂等，可重复执行，不会因「列已存在」「索引已存在」报错；
--   · 历史补丁（booking 升级、手机号唯一、扫码签到、管理端重构）中
--     仍然必需的结构变更，已全部合并进本脚本，不再需要按顺序额外执行；
--   · 已下线且无用的历史补丁（设施表、need_approval 列）不再出现在这里。
--
-- 执行方式：
--   mysql -u root -p < backend/src/main/resources/sql/init.sql
--
-- 说明：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，因此「补列」统一走
-- information_schema 判断 + 动态 SQL，这也是原补丁脚本的写法。
-- ============================================================
CREATE DATABASE IF NOT EXISTS meeting_room DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE meeting_room;

-- ============================================================
-- 一、建表（全新安装时生效；已存在的表不会被改动）
-- ============================================================

-- 用户表
-- uk_phone：手机号全局唯一（一个手机号只能绑定一个微信账号）。
--            MySQL 唯一索引允许多个 NULL，未绑定手机号的用户不受影响。
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    openid VARCHAR(64) DEFAULT NULL COMMENT '微信openid（短信注册用户可为空）',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    nickname VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    avatar VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    status TINYINT DEFAULT 1 COMMENT '状态 1正常 0禁用（永久禁用）',
    violation_count INT NOT NULL DEFAULT 0 COMMENT '连续违约次数（签到成功即归零）',
    banned_until DATETIME DEFAULT NULL COMMENT '禁止预约截止时间，NULL=未禁止',
    ban_source TINYINT DEFAULT NULL COMMENT '本次封禁来源 1连续违约自动 2管理员人工，NULL=未封禁',
    permanent_ban TINYINT NOT NULL DEFAULT 0 COMMENT '是否永久禁用 1是 0否（与限时封禁互斥）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 管理员表
-- 注意：password 长度放到 128，PBKDF2 哈希（pbkdf2$迭代$盐$哈希）约 83 字符
CREATE TABLE IF NOT EXISTS sys_admin (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    username VARCHAR(32) NOT NULL COMMENT '登录账号',
    password VARCHAR(128) NOT NULL COMMENT '密码（PBKDF2，兼容历史MD5）',
    real_name VARCHAR(32) DEFAULT NULL COMMENT '真实姓名',
    role VARCHAR(16) DEFAULT 'admin' COMMENT '角色',
    status TINYINT DEFAULT 1 COMMENT '状态 1正常 0禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员表';

-- 会议室表
CREATE TABLE IF NOT EXISTS meeting_room (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    name VARCHAR(64) NOT NULL COMMENT '会议室名称',
    capacity INT DEFAULT 8 COMMENT '容纳人数',
    location VARCHAR(128) DEFAULT NULL COMMENT '位置',
    image VARCHAR(255) DEFAULT NULL COMMENT '图片URL',
    open_time TIME DEFAULT '08:00:00' COMMENT '开放开始时间（分钟须为 15 的倍数）',
    close_time TIME DEFAULT '22:00:00' COMMENT '开放结束时间（分钟须为 15 的倍数）',
    status TINYINT DEFAULT 1 COMMENT '状态 1启用 0停用',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注（含配备设施说明）',
    sign_code VARCHAR(32) DEFAULT NULL COMMENT '扫码签到码（32位大写HEX，全局唯一）',
    sign_code_time DATETIME DEFAULT NULL COMMENT '签到码最近生成/重置时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_status (status),
    UNIQUE KEY uk_sign_code (sign_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议室表';

-- 预约记录表
CREATE TABLE IF NOT EXISTS booking (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '预约用户ID',
    room_id BIGINT NOT NULL COMMENT '会议室ID',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    status TINYINT DEFAULT 0 COMMENT '状态 0待审批 1已通过 2已驳回 3已取消 4已违约',
    subject VARCHAR(128) DEFAULT NULL COMMENT '会议主题',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因',
    approver_id BIGINT DEFAULT NULL COMMENT '审批人ID',
    approve_time DATETIME DEFAULT NULL COMMENT '审批时间',
    check_in_time DATETIME DEFAULT NULL COMMENT '签到时间',
    remind_time DATETIME DEFAULT NULL COMMENT '临近提醒发送时间（幂等去重标记）',
    cancel_by BIGINT DEFAULT NULL COMMENT '取消操作人（管理员ID；用户本人取消为空）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_room_date (room_id, start_time, status),
    KEY idx_user_status (user_id, status),
    KEY idx_status_start (status, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约记录表';

-- 公告表
CREATE TABLE IF NOT EXISTS announcement (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    title VARCHAR(128) NOT NULL COMMENT '标题',
    content TEXT COMMENT '内容（业务层限制 20000 字符）',
    publisher_id BIGINT DEFAULT NULL COMMENT '发布人ID',
    status TINYINT DEFAULT 1 COMMENT '状态 1上线 0下线',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表';

-- 站内通知表
CREATE TABLE IF NOT EXISTS notification (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '接收用户ID',
    type TINYINT NOT NULL COMMENT '类型 1临近提醒 2违约 3禁止预约',
    title VARCHAR(128) NOT NULL COMMENT '标题',
    content VARCHAR(500) DEFAULT NULL COMMENT '内容',
    biz_id BIGINT DEFAULT NULL COMMENT '关联业务ID（如booking.id）',
    is_read TINYINT DEFAULT 0 COMMENT '是否已读 0未读 1已读',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, is_read, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知表';

-- ============================================================
-- 二、存量库结构补齐（幂等；全新安装时全部跳过）
--     每段先查 information_schema，缺什么补什么。
-- ============================================================

-- 2.1 sys_user.violation_count（连续违约计数）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'violation_count');
SET @s := IF(@c = 0,
  'ALTER TABLE sys_user ADD COLUMN violation_count INT NOT NULL DEFAULT 0 COMMENT ''连续违约次数'' AFTER status',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.2 sys_user.banned_until（禁止预约截止时间）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'banned_until');
SET @s := IF(@c = 0,
  'ALTER TABLE sys_user ADD COLUMN banned_until DATETIME DEFAULT NULL COMMENT ''禁止预约截止时间'' AFTER violation_count',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.3 sys_user.ban_source（封禁来源：区分「连续违约自动封禁」与「管理员手动封禁」）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'ban_source');
SET @s := IF(@c = 0,
  'ALTER TABLE sys_user ADD COLUMN ban_source TINYINT DEFAULT NULL COMMENT ''封禁来源 1连续违约 2管理员人工'' AFTER banned_until',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.4 sys_user.permanent_ban（永久禁用独立标记，避免被限时封禁/解封误清）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'permanent_ban');
SET @s := IF(@c = 0,
  'ALTER TABLE sys_user ADD COLUMN permanent_ban TINYINT NOT NULL DEFAULT 0 COMMENT ''是否永久禁用 1是'' AFTER ban_source',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.5 历史永久禁用数据回填：status=0 的老数据默认按永久禁用对待
--     否则升级后这些用户在管理端会显示为「正常」，可再次被封禁/解封。
UPDATE sys_user SET permanent_ban = 1 WHERE status = 0 AND permanent_ban = 0;

-- 2.6 booking.remind_time（临近提醒去重标记）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking' AND COLUMN_NAME = 'remind_time');
SET @s := IF(@c = 0,
  'ALTER TABLE booking ADD COLUMN remind_time DATETIME DEFAULT NULL COMMENT ''临近提醒发送时间'' AFTER check_in_time',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.7 booking.cancel_by（管理端取消操作人，用于追责）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking' AND COLUMN_NAME = 'cancel_by');
SET @s := IF(@c = 0,
  'ALTER TABLE booking ADD COLUMN cancel_by BIGINT DEFAULT NULL COMMENT ''取消操作人（管理员ID）'' AFTER remind_time',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.8 meeting_room.sign_code / sign_code_time（扫码签到）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'meeting_room' AND COLUMN_NAME = 'sign_code');
SET @s := IF(@c = 0,
  'ALTER TABLE meeting_room ADD COLUMN sign_code VARCHAR(32) DEFAULT NULL COMMENT ''扫码签到码'' AFTER remark,
                        ADD COLUMN sign_code_time DATETIME DEFAULT NULL COMMENT ''签到码生成时间'' AFTER sign_code',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2.9 回填缺失的签到码
--     不用 UUID()：MySQL 的 UUID() 是 v1，同机连续生成时后半段几乎相同，可被推算，
--     作为签到令牌毫无安全性。改用 MD5(RAND()+UUID()+id+NOW(6))。
UPDATE meeting_room
SET sign_code = UPPER(MD5(CONCAT(RAND(), UUID(), id, NOW(6)))),
    sign_code_time = NOW()
WHERE sign_code IS NULL OR sign_code = '';

-- 2.10 索引补齐：uk_sign_code / idx_status_start / uk_phone
SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'meeting_room' AND INDEX_NAME = 'uk_sign_code');
SET @s := IF(@i = 0,
  'ALTER TABLE meeting_room ADD UNIQUE KEY uk_sign_code (sign_code)',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking' AND INDEX_NAME = 'idx_status_start');
SET @s := IF(@i = 0,
  'ALTER TABLE booking ADD KEY idx_status_start (status, start_time)',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 手机号唯一：老库是 idx_phone 普通索引，需换成 uk_phone 唯一索引。
-- 若存在重复手机号，ALTER 会失败并提示 Duplicate entry —— 那是真实脏数据，
-- 先执行下面的查重语句人工处理后重跑本脚本。
SELECT phone, COUNT(*) AS cnt
FROM sys_user
WHERE phone IS NOT NULL AND phone <> '' AND deleted = 0
GROUP BY phone
HAVING COUNT(*) > 1;

SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_phone');
SET @s := IF(@i = 0,
  'ALTER TABLE sys_user ADD UNIQUE KEY uk_phone (phone)',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 旧的普通索引在唯一索引就位后已无意义，存在则删掉（避免两条同列索引）
SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'idx_phone');
SET @s := IF(@i > 0,
  'ALTER TABLE sys_user DROP INDEX idx_phone',
  'SELECT 1 INTO @noop');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============================================================
-- 三、初始化数据（全部幂等：已存在则跳过）
-- ============================================================

-- 初始管理员（默认密码：123456）
-- 首次登录后系统会自动将其升级为 PBKDF2 哈希，无需手工处理
-- 【提醒】首次登录后请立即在管理端修改密码，默认密码只适用于初始化。
INSERT INTO sys_admin (username, password, real_name, role, status)
SELECT 'admin', MD5('123456'), '系统管理员', 'admin', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_admin WHERE username = 'admin');

-- 示例会议室（设施说明直接写在备注里；开放时间必须与 15 分钟预约粒度对齐）
INSERT INTO meeting_room (name, capacity, location, open_time, close_time, status, remark)
SELECT '第一会议室', 8, 'A栋 3楼 301', '08:00:00', '22:00:00', 1, '小型会议室；配备：投影仪、白板' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM meeting_room WHERE name = '第一会议室' AND deleted = 0);

INSERT INTO meeting_room (name, capacity, location, open_time, close_time, status, remark)
SELECT '第二会议室', 12, 'A栋 3楼 302', '08:00:00', '22:00:00', 1, '中型会议室；配备：投影仪、白板、视频会议' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM meeting_room WHERE name = '第二会议室' AND deleted = 0);

INSERT INTO meeting_room (name, capacity, location, open_time, close_time, status, remark)
SELECT '多功能厅', 20, 'B栋 1楼 101', '08:00:00', '22:00:00', 1, '大型会议室；配备：投影仪、视频会议、音响、电视' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM meeting_room WHERE name = '多功能厅' AND deleted = 0);

INSERT INTO meeting_room (name, capacity, location, open_time, close_time, status, remark)
SELECT '洽谈室', 4, 'A栋 2楼 205', '08:00:00', '22:00:00', 1, '小型洽谈室' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM meeting_room WHERE name = '洽谈室' AND deleted = 0);

-- 新插入的会议室补签到码（上面 2.9 段已在升级场景执行过，这里覆盖「全新安装」场景）
UPDATE meeting_room
SET sign_code = UPPER(MD5(CONCAT(RAND(), UUID(), id, NOW(6)))),
    sign_code_time = NOW()
WHERE sign_code IS NULL OR sign_code = '';

-- 示例公告
INSERT INTO announcement (title, content, publisher_id, status)
SELECT '会议室预约系统上线通知',
       '各位同事，会议室预约系统已正式上线，请通过微信小程序进行会议室预约，谢谢配合！',
       1, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM announcement WHERE title = '会议室预约系统上线通知');

-- ============================================================
-- 四、自检（结果应全部符合预期，不符合说明脚本没跑完）
-- ============================================================
-- 4.1 关键列是否齐全（应返回 6）
SELECT COUNT(*) AS user_key_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
  AND COLUMN_NAME IN ('violation_count', 'banned_until', 'ban_source', 'permanent_ban', 'phone', 'status');

-- 4.2 每间在用会议室都必须有签到码且互不相同
SELECT COUNT(*) AS room_total,
       SUM(sign_code IS NULL OR sign_code = '') AS room_without_code,
       COUNT(DISTINCT sign_code) AS distinct_code
FROM meeting_room WHERE deleted = 0;

-- 4.3 通知表存在（应返回 1）
SELECT COUNT(*) AS notification_table FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification';
