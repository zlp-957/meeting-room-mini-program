package com.meeting.common;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 登录身份工具
 *
 * <p>用 loginId 前缀区分「管理员」与「用户」两套身份，避免 sys_admin.id 与 sys_user.id
 * 同值时的跨角色越权（P1-4）：管理员 token 不会再被当成普通用户。</p>
 */
public final class LoginHelper {

    public static final String ADMIN_PREFIX = "admin:";
    public static final String USER_PREFIX = "user:";

    private LoginHelper() {
    }

    /** 管理员登录 */
    public static void loginAsAdmin(Long adminId) {
        StpUtil.login(ADMIN_PREFIX + adminId);
        StpUtil.getSession().set("role", "admin");
    }

    /** 用户登录 */
    public static void loginAsUser(Long userId) {
        StpUtil.login(USER_PREFIX + userId);
        StpUtil.getSession().set("role", "user");
    }

    /** 当前 loginId 原始值 */
    public static String rawLoginId() {
        return String.valueOf(StpUtil.getLoginId());
    }

    public static boolean isAdmin() {
        return rawLoginId().startsWith(ADMIN_PREFIX);
    }

    public static boolean isUser() {
        return rawLoginId().startsWith(USER_PREFIX);
    }

    /** 当前管理员 ID（非管理员身份调用会抛 403） */
    public static Long adminId() {
        return parse(ADMIN_PREFIX);
    }

    /** 当前用户 ID（非用户身份调用会抛 403） */
    public static Long userId() {
        return parse(USER_PREFIX);
    }

    private static Long parse(String prefix) {
        String raw = rawLoginId();
        if (!raw.startsWith(prefix)) {
            throw new BusinessException(403, "当前登录身份无权执行该操作");
        }
        return Long.valueOf(raw.substring(prefix.length()));
    }
}
