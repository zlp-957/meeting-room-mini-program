-- 短信验证码登录方案：数据库结构迁移脚本
-- 用途：把 sys_user 表从「依赖微信 openid」调整为「支持手机号登录」。
-- 对已存在的库执行一次即可（幂等，可重复执行）。

USE meeting_room;

-- 1. openid 改为可空（短信注册用户没有 openid）
ALTER TABLE sys_user MODIFY COLUMN openid VARCHAR(64) DEFAULT NULL COMMENT '微信openid（短信注册用户可为空）';

-- 2. 移除 openid 唯一约束（允许多个 NULL 并存，MySQL 唯一索引本身允许多 NULL，这里为稳妥先删）
--    注意：uk_openid 若不存在会报错，用存储过程判断后删除
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND index_name = 'uk_openid') > 0,
    'ALTER TABLE sys_user DROP INDEX uk_openid',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. phone 加普通索引（若不存在）
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND index_name = 'idx_phone') > 0,
    'SELECT 1',
    'ALTER TABLE sys_user ADD INDEX idx_phone (phone)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 说明：phone 未加唯一索引，是为了兼容「同一手机号可能既有微信登录又有短信登录」的场景。
--    短信登录逻辑里用 SELECT ... WHERE phone = ? 查询，若命中多条取第一条（建议业务上保证手机号唯一）。
--    如需强制手机号唯一，可取消下面注释：
-- ALTER TABLE sys_user ADD UNIQUE INDEX uk_phone (phone);
