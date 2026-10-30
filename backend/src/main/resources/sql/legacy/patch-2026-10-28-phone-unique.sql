-- 2026-10-28 补丁：手机号全局唯一
-- 背景：登录改为微信授权登录，手机号在「个人中心」绑定；一个手机号只能绑定一个微信账号
-- 执行前请确认没有重复手机号，脚本最后会输出检查结果

-- 1. 检查重复（正常应返回 0 行）
SELECT phone, COUNT(*) AS cnt
FROM sys_user
WHERE phone IS NOT NULL AND phone <> '' AND deleted = 0
GROUP BY phone
HAVING COUNT(*) > 1;

-- 2. 去掉旧普通索引，建唯一索引（MySQL 允许多个 NULL，未绑定用户不受影响）
ALTER TABLE sys_user DROP INDEX idx_phone;
ALTER TABLE sys_user ADD UNIQUE KEY uk_phone (phone);
