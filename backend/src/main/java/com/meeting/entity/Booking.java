package com.meeting.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("booking")
public class Booking {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roomId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0待审批 1已通过 2已驳回 3已取消 4已违约 */
    private Integer status;

    private String subject;

    private String rejectReason;

    private Long approverId;

    private LocalDateTime approveTime;

    private LocalDateTime checkInTime;

    /** 临近提醒发送时间（幂等去重标记） */
    private LocalDateTime remindTime;

    /**
     * 取消操作人（管理员 ID）。
     *
     * <p>用户本人取消时为空；管理员代取消时必须留痕，否则事后无法回答
     * 「这单是谁取消的」。不能用 approverId 代替 —— 那是审批动作的记录，语义不同。</p>
     */
    private Long cancelBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /** 非数据库字段：管理端列表展示用 */
    @TableField(exist = false)
    private String roomName;

    /** 非数据库字段：管理端列表展示用 */
    @TableField(exist = false)
    private String userName;

    /**
     * 非数据库字段：管理端"通过"按钮是否可用（由后端统一计算，前端只读）
     *
     * <p>审批规则放在后端，避免前端用本机时钟判断导致"按钮可点但接口必失败"。</p>
     */
    @TableField(exist = false)
    private Boolean canApprove;

    /** 非数据库字段：管理端"驳回"按钮是否可用（由后端统一计算，前端只读） */
    @TableField(exist = false)
    private Boolean canReject;

    /**
     * 非数据库字段：管理端"取消预约"按钮是否可用。
     *
     * <p>与用户端 {@code canCancel} 不是同一套规则：用户受「开始前 2 小时」限制，
     * 管理员是兜底通道，只要还没开始、没签到就允许，所以必须单独一个字段。</p>
     */
    @TableField(exist = false)
    private Boolean canCancelByAdmin;

    /**
     * 非数据库字段：当前用户此刻是否可以扫码签到（由后端统一计算，小程序只读）。
     *
     * <p>与 canApprove / canReject 同一原则：签到窗口的判定放在后端，
     * 前端不再硬编码「提前 15 分钟 / 迟到 15 分钟」，改配置不需要发小程序版本。</p>
     */
    @TableField(exist = false)
    private Boolean canSign;

    /**
     * 非数据库字段：签到开始时刻 = 开始时间 - sign-window-minutes。
     *
     * <p>下发给小程序只用于「停在页面时本地重算按钮显隐」，
     * 边界值本身来自后端配置，前端不存在第二份规则。</p>
     */
    @TableField(exist = false)
    private LocalDateTime signOpenAt;

    /** 非数据库字段：签到截止时刻 = 开始时间 + sign-late-minutes，超过即违约 */
    @TableField(exist = false)
    private LocalDateTime signDeadline;

    /**
     * 非数据库字段：此刻是否已进入签到窗口。
     *
     * <p>与 {@code canSign} 的区别是本字段只表示「时间到没到」，不表示「能不能签」。
     * 小程序据此决定点击行为：{@code canSign=true} 但 {@code signWindowOpen=false}
     * 说明按钮该显示但还不能扫，点击时提示「几点开始」，而不是让用户白扫一次。</p>
     */
    @TableField(exist = false)
    private Boolean signWindowOpen;

    /**
     * 非数据库字段：当前用户此刻是否可以修改这条预约（由后端统一计算，小程序只读）。
     *
     * <p>规则：状态为待审批/已通过、尚未签到、且距离开始还有 cancel-before-hours 小时以上
     * （与取消同一时间门槛，保证管理员仍有时间重新审核）。</p>
     */
    @TableField(exist = false)
    private Boolean canEdit;

    /**
     * 非数据库字段：当前用户此刻是否可以取消（由后端统一计算，小程序只读）。
     *
     * <p>原先由小程序本地按 {@code startTime - now > 2h} 自行判断，
     * 手机时间被手动改过就会出现「按钮能点、接口必失败」。改为后端下发。</p>
     */
    @TableField(exist = false)
    private Boolean canCancel;

    /**
     * 非数据库字段：管理端签到状态。
     * 0 无需签到 / 1 未签到 / 2 已签到 / 3 超时未签到 / 4 已违约
     *
     * <p>只下发数字，中文文案由前端映射（与 statusText 一致）；
     * 但「是否已超时」依赖服务器时钟，必须后端算，与 canApprove 同理。</p>
     */
    @TableField(exist = false)
    private Integer signState;
}
