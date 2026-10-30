package com.meeting.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WxLoginDTO {
    /** 微信登录 code */
    @NotBlank(message = "code 不能为空")
    private String code;
}
