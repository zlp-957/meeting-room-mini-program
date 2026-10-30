package com.meeting.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议室某日的已占用时段（用户端「选择时间」页专用）。
 *
 * <p><b>为什么不能直接返回 Booking 实体</b>：实体里有 {@code userId / subject / approverId /
 * approveTime / checkInTime / rejectReason}。这个接口任何登录用户都能按会议室 + 日期枚举，
 * 于是「谁在几点开什么会、有没有签到、被谁批的」全部可被批量爬取 ——
 * 会议室时间表本身是公开信息，但「谁开的什么会」不是。</p>
 *
 * <p>因此这里只保留前端真正需要的三个字段：占用区间 + 状态。
 * 需要区分「待审批」与「已通过」时用 status，不再需要任何用户维度信息。</p>
 */
@Data
public class BookingSlotVO {

    /** 会议室 ID（前端已知道，回传便于按房间聚合渲染） */
    private Long roomId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0 待审批 / 1 已通过 —— 两者都占用时段，前端按同一规则置灰即可 */
    private Integer status;
}
