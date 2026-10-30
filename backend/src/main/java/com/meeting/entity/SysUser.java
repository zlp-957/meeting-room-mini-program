package com.meeting.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String openid;

    private String phone;

    private String nickname;

    private String avatar;

    private Integer status;

    /** 连续违约次数（签到成功即归零） */
    private Integer violationCount;

    /** 禁止预约截止时间（NULL=未禁止） */
    private LocalDateTime bannedUntil;

    /**
     * 本次封禁来源：1 连续违约自动封禁 / 2 管理员人工封禁 / NULL 未封禁。
     *
     * <p>没有这个字段时，提示语只能写死「您因连续违约已被禁止预约」，
     * 被管理员手动封禁的用户会以为是系统误判自己违约，投诉量会明显上升。</p>
     */
    private Integer banSource;

    /**
     * 是否永久禁用（1 是 / 0 否）。
     *
     * <p>为什么不用 {@code status=0} 单独表达：status 既表示「账号可登录」又隐含「永久禁用」，
     * 导致「给永久禁用用户改 1 天封禁」会把 status 改回 1，等于顺手解禁；
     * 解封接口同理。拆成独立标记后，两种封禁互不覆盖。</p>
     */
    private Integer permanentBan;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
