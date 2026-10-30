package com.meeting.common;

/**
 * 封禁来源常量（对应 {@code sys_user.ban_source}）。
 *
 * <p>为什么要区分来源：同一句「您因连续违约已被禁止预约」对自动封禁是正确的，
 * 对管理员手动封禁的用户就是错误归因 —— 用户会去申诉「我没有违约」，
 * 而管理员在后台也说不清是谁封的。来源不同，提示文案与申诉入口都不同。</p>
 */
public final class BanSource {

    /** 连续违约达到阈值，系统自动封禁 */
    public static final int VIOLATION = 1;

    /** 管理员在后台手动封禁 */
    public static final int MANUAL = 2;

    private BanSource() {
    }
}
