package com.meeting.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议室签到码（管理端展示 / 打印用）。
 *
 * <p>{@code qrContent} 由后端拼装，格式只有 {@code SignCodeUtil} 一处定义，
 * 管理后台只负责把它交给二维码库渲染，不参与拼接 —— 否则将来改格式要同时改前后端。</p>
 */
@Data
public class RoomSignCodeVO {

    private Long roomId;

    private String roomName;

    private String location;

    /** 32 位大写 HEX，同时以文本形式展示，便于人工核对与电话报码兜底 */
    private String signCode;

    /** 二维码原始内容 */
    private String qrContent;

    /** 最近生成 / 重置时间 */
    private LocalDateTime signCodeTime;
}
