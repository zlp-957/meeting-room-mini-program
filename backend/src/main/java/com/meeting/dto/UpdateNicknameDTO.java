package com.meeting.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 修改昵称请求
 *
 * <p>长度与 {@code sys_user.nickname VARCHAR(64)} 对齐：
 * 中文按字符计，这里收紧到 20 个字符，既能容纳正常昵称，也避免超长昵称撑破界面。
 * 具体内容合规（辱骂、冒充官方、广告引流等）由 {@code NicknameCheckService} 二次校验。</p>
 */
@Data
public class UpdateNicknameDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 20, message = "昵称长度需为 2-20 个字符")
    private String nickname;
}
