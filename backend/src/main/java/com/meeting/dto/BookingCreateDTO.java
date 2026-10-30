package com.meeting.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
public class BookingCreateDTO {
    @NotNull(message = "会议室不能为空")
    private Long roomId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** P1-12：限制会议主题长度，避免超长文本在落库时抛 500 */
    @NotBlank(message = "会议主题不能为空")
    @Size(max = 100, message = "会议主题不能超过 100 个字符")
    private String subject;
}
