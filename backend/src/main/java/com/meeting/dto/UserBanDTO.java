package com.meeting.dto;

import lombok.Data;

/**
 * 管理端封禁用户的入参
 */
@Data
public class UserBanDTO {

    /**
     * 封禁天数：1 / 7 / 30。
     * 传 null 或小于等于 0 表示「永久禁用」。
     */
    private Integer days;
}
