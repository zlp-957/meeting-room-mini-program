package com.meeting.dto;

import lombok.Data;

/**
 * 扫码签到请求体。
 *
 * <p>刻意不加 {@code @NotBlank}：老版本小程序调用签到接口时不带请求体，
 * 如果交给 Bean Validation，返回的是框架的「参数校验失败」这类对终端用户无意义的提示。
 * 由 Controller 手工判空，可以给出「请使用最新版本小程序扫码签到」这种可行动的话。</p>
 */
@Data
public class BookingSignDTO {

    /** 扫码原文，格式 MR|1|<roomId>|<32位HEX签到码> */
    private String code;
}
