package com.meeting.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 绑定 / 修改手机号入参。
 *
 * <p>只填号码，不做短信验证 —— 前提是手机号<b>不作为登录凭据</b>（登录只走微信授权）。
 * 若将来要启用手机号登录，必须先把验证码校验加回来，否则「谁先绑定谁拥有」会变成
 * 完整的账号接管链路。</p>
 */
@Data
public class BindPhoneDTO {

    /** 用户手动输入的手机号（11 位，1 开头） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;
}
