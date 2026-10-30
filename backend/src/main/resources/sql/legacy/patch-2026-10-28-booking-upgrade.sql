-- 会议室预约功能改造迁移脚本（2026-10-28）
-- 覆盖：营业时间统一 08:00-22:00、违约封禁字段、临近提醒字段、站内通知表
-- 说明：本脚本可重复执行（幂等）

USE meeting_room;

-- ============================================================
-- 1. 营业时间统一 08:00 - 22:00（所有启用会议室）
-- ============================================================
UPDATE meeting_room SET open_time = '08:00:00', close_time = '22:00:00' WHERE deleted = 0;

-- 同步列默认值（新建会议室默认 08:00-22:00）
ALTER TABLE meeting_room
  MODIFY COLUMN open_time  TIME DEFAULT '08:00:00' COMMENT '开放开始时间',
  MODIFY COLUMN close_time TIME DEFAULT '22:00:00' COMMENT '开放结束时间';

-- ============================================================
-- 2. 用户表：连续违约计数 + 封禁截止时间
-- ============================================================
-- 2.1 连续违约次数（签到成功即归零，见 BookingService.sign）
ALTER TABLE sys_user
  ADD COLUMN violation_count INT NOT NULL DEFAULT 0 COMMENT '连续违约次数' AFTER status;

-- 2.2 禁止预约截止时间（NULL=未禁止）
ALTER TABLE sys_user
  ADD COLUMN banned_until DATETIME DEFAULT NULL COMMENT '禁止预约截止时间，NULL=未禁止' AFTER violation_count;

-- ============================================================
-- 3. 预约表：临近提醒发送时间（幂等去重标记）
-- ============================================================
ALTER TABLE booking
  ADD COLUMN remind_time DATETIME DEFAULT NULL COMMENT '临近提醒发送时间' AFTER check_in_time;

-- ============================================================
-- 4. 站内通知表
-- ============================================================
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
