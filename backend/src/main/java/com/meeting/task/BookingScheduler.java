package com.meeting.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.meeting.common.BookingStatus;
import com.meeting.common.SignWindow;
import com.meeting.entity.Booking;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.SysUser;
import com.meeting.mapper.BookingMapper;
import com.meeting.mapper.MeetingRoomMapper;
import com.meeting.mapper.SysUserMapper;
import com.meeting.service.NotificationService;
import com.meeting.service.SubscribeMessageService;
import com.meeting.service.ViolationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 预约定时任务
 *
 * <p>负责：违约巡检、临近未签到提醒、封禁到期自动解封。</p>
 *
 * <p>违约判定逻辑本身在 {@link ViolationService}，本类只负责「什么时候查、查哪些」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingScheduler {

    /** 单轮违约巡检上限：防止服务长时间停机恢复后，一轮把历史积压全量处理造成瞬时洪水 */
    private static final int VIOLATION_BATCH_LIMIT = 200;

    /** 临近提醒提前分钟数 */
    @Value("${meeting.remind-before-minutes:30}")
    private int remindBeforeMinutes;

    private final BookingMapper bookingMapper;
    private final SysUserMapper userMapper;
    private final MeetingRoomMapper roomMapper;
    private final NotificationService notificationService;
    private final SubscribeMessageService subscribeMessageService;
    private final ViolationService violationService;
    private final SignWindow signWindow;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HM_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 违约巡检：每分钟执行一次。
     *
     * <p>判定口径：{@code status=1 且未签到 且 会议开始已超过 sign-late-minutes 分钟}。
     * 注意这是「开始后 15 分钟」，<b>不再是「会议结束后」</b> —— 会议进行中也可能被判定违约，
     * 因为签到窗口在开始后 15 分钟就关闭了，之后的出席不再计入履约。</p>
     *
     * <p>频率从每 10 分钟改成每分钟：违约的实际意义是「立刻把没人用的会议室放出来」，
     * 10 分钟延迟意味着这间房有 10 分钟处于「显示占用但无人使用」的浪费状态。
     * 配合新增的 {@code idx_status_start(status, start_time)} 索引，单次扫描代价可忽略。</p>
     */
    @Scheduled(cron = "0 * * * * ?")
    public void markViolated() {
        LocalDateTime now = LocalDateTime.now();
        // 超时违约 ⟺ now > start + lateMinutes ⟺ start < now - lateMinutes
        LocalDateTime cutoff = now.minusMinutes(signWindow.getLateMinutes());
        List<Booking> candidates = bookingMapper.selectList(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getStatus, BookingStatus.APPROVED)
                        .isNull(Booking::getCheckInTime)
                        .lt(Booking::getStartTime, cutoff)
                        .orderByAsc(Booking::getStartTime)
                        .last("LIMIT " + VIOLATION_BATCH_LIMIT));

        if (candidates.isEmpty()) {
            return;
        }
        int done = violationService.markViolatedBatch(candidates, now);
        log.info("违约巡检：候选 {} 条，实际判定 {} 条", candidates.size(), done);
    }

    /**
     * 启动补偿：服务就绪后立刻跑一轮违约巡检。
     *
     * <p>所有判定条件都是绝对时间比较（不依赖"上次执行时间"），所以停机期间漏判的记录
     * 在恢复后第一轮就会被全量命中。这里额外补一次，把「启动到第一个整分钟」的窗口也盖住。</p>
     *
     * <p>用 try-catch 包住：补偿失败绝不能影响服务启动。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void markViolatedOnBoot() {
        try {
            markViolated();
        } catch (Exception e) {
            log.error("启动补偿违约巡检失败", e);
        }
    }

    /**
     * 临近未签到提醒：每 5 分钟执行一次。
     *
     * <p>通过 remind_time 幂等去重，避免重复提醒。文案里给出<b>签到截止时刻</b>
     * 而不是"请尽快签到" —— 用户真正需要知道的是"几点之前必须去门口扫码"。</p>
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void remindNoCheckIn() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusMinutes(remindBeforeMinutes);
        List<Booking> toRemind = bookingMapper.selectList(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getStatus, BookingStatus.APPROVED)
                        .isNull(Booking::getCheckInTime)
                        .isNull(Booking::getRemindTime)
                        .gt(Booking::getStartTime, now)
                        .le(Booking::getStartTime, windowEnd));

        for (Booking b : toRemind) {
            // 先写 remind_time 再发通知，保证幂等（宁可漏发，不重复轰炸）
            b.setRemindTime(now);
            bookingMapper.updateById(b);

            String startStr = b.getStartTime().format(DT_FMT);
            LocalDateTime deadline = signWindow.deadline(b.getStartTime());
            String deadlineStr = deadline == null ? "" : deadline.format(HM_FMT);

            notificationService.push(b.getUserId(), 1, "会议即将开始提醒",
                    "您预约的「" + ViolationService.subjectText(b) + "」将于 " + startStr
                            + " 开始，请在 " + deadlineStr + " 前扫描会议室门口的二维码完成签到，逾期将判定违约。",
                    b.getId());

            SysUser user = userMapper.selectById(b.getUserId());
            if (user != null) {
                MeetingRoom room = roomMapper.selectById(b.getRoomId());
                subscribeMessageService.sendRemind(user.getOpenid(), b.getSubject(),
                        room == null ? null : room.getName(), startStr);
            }
        }
        if (!toRemind.isEmpty()) {
            log.info("发送临近签到提醒 {} 条", toRemind.size());
        }
    }

    /**
     * 自动解封：每小时执行一次，把封禁到期的用户解封并重置违约计数。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void autoUnban() {
        LocalDateTime now = LocalDateTime.now();
        List<SysUser> expired = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .isNotNull(SysUser::getBannedUntil)
                        .le(SysUser::getBannedUntil, now));
        for (SysUser u : expired) {
            // 必须用 UpdateWrapper 显式 set null：默认 NOT_NULL 更新策略下 updateById 会跳过 null 字段，
            // 导致 banned_until 永远清不掉、封禁到期也不会真正解封
            userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, u.getId())
                    .set(SysUser::getBannedUntil, null)
                    // 来源一起清掉：否则下次提示仍按「管理员人工封禁」的文案下发，与事实不符
                    .set(SysUser::getBanSource, null)
                    .set(SysUser::getViolationCount, 0));
            notificationService.push(u.getId(), 3, "预约权限已恢复",
                    "您的会议室预约权限已恢复，可正常预约。", null);
            log.info("用户 {} 封禁到期，自动解封", u.getId());
        }
    }
}
