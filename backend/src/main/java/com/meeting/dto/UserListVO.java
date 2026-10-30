package com.meeting.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端用户列表项。
 *
 * <p>不用 {@code SysUser} 实体的原因：实体带 {@code openid}，而 openid 是微信账号的唯一标识，
 * 一旦泄露给管理端页面（含浏览器缓存、复制粘贴、日志），等于泄露了「如何定位某个真实微信用户」的钥匙。
 * 后台任何页面都不需要它，因此从列表接口层面就不返回。</p>
 */
@Data
public class UserListVO {

    private Long id;

    private String nickname;

    private String phone;

    private String avatar;

    /** 1 正常 0 禁用 */
    private Integer status;

    /** 连续违约次数 */
    private Integer violationCount;

    /** 限时封禁截止时间 */
    private LocalDateTime bannedUntil;

    /** 封禁来源：1 连续违约自动 / 2 管理员人工 / null 未封禁 */
    private Integer banSource;

    /** 是否永久禁用 */
    private Integer permanentBan;

    private LocalDateTime createTime;
}
