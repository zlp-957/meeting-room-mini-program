package com.meeting.dto;

import com.meeting.entity.Booking;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端：用户详情（基础资料 + 预约统计 + 最近预约）
 *
 * <p>不包含 openid：微信标识对管理端没有用途，避免不必要的信息暴露。</p>
 */
@Data
public class UserDetailVO {

    private Long id;

    private String nickname;

    private String phone;

    private String avatar;

    /** 账号状态：1 正常 0 禁用（永久） */
    private Integer status;

    /** 连续违约次数 */
    private Integer violationCount;

    /** 禁止预约截止时间（null 表示未限制） */
    private LocalDateTime bannedUntil;

    /** 封禁来源：1 连续违约自动 / 2 管理员人工 / null 未封禁 */
    private Integer banSource;

    /** 是否永久禁用：1 是 0 否 */
    private Integer permanentBan;

    private LocalDateTime createTime;

    /** 是否处于封禁状态（限时封禁未到期 或 已永久禁用） */
    private Boolean banned;

    /** 封禁类型：permanent 永久禁用 / temporary 限时封禁 / null 正常 */
    private String banType;

    /** 预约总数 */
    private Long total;

    private Long pending;

    private Long approved;

    private Long rejected;

    private Long canceled;

    private Long violated;

    /** 未结束的有效预约数（待审批 + 已通过，且结束时间晚于当前时间） */
    private Long upcoming;

    /** 最近预约（按开始时间倒序，最多 10 条） */
    private List<Booking> recentBookings;
}
