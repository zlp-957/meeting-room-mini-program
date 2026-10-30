package com.meeting.common;

/**
 * 预约状态常量，与 booking.status 注释保持一致。
 *
 * <p>历史代码中状态值以字面量散落在各处，本类仅供新增的状态流转逻辑使用，
 * 不做全量替换，避免产生与功能无关的改动。</p>
 */
public final class BookingStatus {

    /** 待审批 */
    public static final int PENDING = 0;
    /** 已通过 */
    public static final int APPROVED = 1;
    /** 已驳回 */
    public static final int REJECTED = 2;
    /** 已取消 */
    public static final int CANCELED = 3;
    /** 已违约 */
    public static final int VIOLATED = 4;

    private BookingStatus() {
    }
}
