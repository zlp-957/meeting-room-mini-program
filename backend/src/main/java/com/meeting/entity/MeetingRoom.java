package com.meeting.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("meeting_room")
public class MeetingRoom {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer capacity;

    private String location;

    private String image;

    private LocalTime openTime;

    private LocalTime closeTime;

    private Integer status;

    private String remark;

    /**
     * 扫码签到码（32 位大写 HEX，唯一）。
     *
     * <p><b>必须 @JsonIgnore</b>：本实体被免登录接口 {@code GET /api/room/list}、
     * {@code GET /api/room/{id}} 直接序列化返回，不加此注解等于把全站签到码公开，
     * 用户在家就能自己拼出二维码，"必须到现场扫码"的整条防线直接作废。</p>
     * 管理端需要展示时，走 {@code RoomSignCodeVO} 单独接口下发。
     */
    @JsonIgnore
    private String signCode;

    /** 签到码最近生成 / 重置时间，用于追溯贴纸新旧 */
    @JsonIgnore
    private LocalDateTime signCodeTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
