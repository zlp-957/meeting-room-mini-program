package com.meeting.common;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 签到时间窗口 —— 全系统唯一事实来源。
 *
 * <p>窗口 = [会议开始前 beforeMinutes 分钟, 会议开始后 lateMinutes 分钟]。</p>
 *
 * <p>之所以单独抽一个 Bean：签到窗口原先在两处各写一份（{@code BookingService.sign()} 判定、
 * 小程序硬编码 {@code SIGN_WINDOW_MINUTES}），改配置必须手工同步三处，漏一处就会出现
 * 「按钮可点但接口必失败」。现在 {@code BookingService} 与 {@code BookingScheduler}
 * 都注入本类，小程序改为读取后端下发的 {@code signOpenAt / signDeadline}，规则只有一份。</p>
 */
@Getter
@Component
public class SignWindow {

    /** 会议开始前提前开放签到的分钟数 */
    @Value("${meeting.sign-window-minutes:15}")
    private int beforeMinutes;

    /** 会议开始后仍可签到的分钟数，超过即判定违约 */
    @Value("${meeting.sign-late-minutes:15}")
    private int lateMinutes;

    /** 签到开始时刻 = 会议开始时间 - beforeMinutes */
    public LocalDateTime openAt(LocalDateTime start) {
        if (start == null) {
            return null;
        }
        return start.minusMinutes(beforeMinutes);
    }

    /**
     * 签到截止时刻 = 会议开始时间 + lateMinutes。
     *
     * <p>注意：这个值【不再是】会议结束时间。超过它仍未签到即违约。</p>
     */
    public LocalDateTime deadline(LocalDateTime start) {
        if (start == null) {
            return null;
        }
        return start.plusMinutes(lateMinutes);
    }

    /**
     * 当前是否处于可签到区间（含两端）。
     *
     * <p>用 {@code !isBefore} / {@code !isAfter} 而不是 {@code isBefore} 取反，
     * 保证正好等于 openAt / deadline 的那一秒仍算有效，避免边界抖 1 毫秒。</p>
     */
    public boolean isOpen(LocalDateTime start, LocalDateTime now) {
        if (start == null || now == null) {
            return false;
        }
        return !now.isBefore(openAt(start)) && !now.isAfter(deadline(start));
    }

    /** 是否已超过签到截止时刻（超时即违约，且不可再补签） */
    public boolean isTimeout(LocalDateTime start, LocalDateTime now) {
        if (start == null || now == null) {
            return false;
        }
        return now.isAfter(deadline(start));
    }
}
