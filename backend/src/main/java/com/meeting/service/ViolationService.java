package com.meeting.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.meeting.common.BanSource;
import com.meeting.common.BookingStatus;
import com.meeting.common.SignWindow;
import com.meeting.entity.Booking;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.SysUser;
import com.meeting.mapper.BookingMapper;
import com.meeting.mapper.MeetingRoomMapper;
import com.meeting.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 违约判定服务。
 *
 * <p>抽出独立 Bean 的原因有两个：</p>
 * <ol>
 *   <li>违约判定有三个触发点 —— 定时巡检、用户超时点签到时当场判定、
 *       未来可能的管理端手动判定。逻辑若留在 {@code BookingScheduler} 里就只有一处能用。</li>
 *   <li><b>事务边界</b>：{@code BookingService.sign()} 在超时分支需要「先判违约、再抛业务异常」。
 *       如果它自己带 {@code @Transactional}，抛异常会把刚写的违约标记一起回滚，
 *       等于什么都没做。判定逻辑放在本 Bean（自带事务），跨 Bean 调用时事务独立提交，
 *       外层再抛异常也不影响已提交的违约记录。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationService {

    /** 连续违约达到该次数即禁止预约 */
    @Value("${meeting.violation-ban-threshold:3}")
    private int banThreshold;

    /** 禁止预约时长（天） */
    @Value("${meeting.violation-ban-days:30}")
    private int banDays;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HM_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final BookingMapper bookingMapper;
    private final SysUserMapper userMapper;
    private final MeetingRoomMapper roomMapper;
    private final NotificationService notificationService;
    private final SubscribeMessageService subscribeMessageService;
    private final SignWindow signWindow;

    /**
     * 判定单条预约为违约。
     *
     * <p>用条件更新（{@code status=1 AND check_in_time IS NULL}）而不是 {@code updateById}，
     * 这是幂等的关键：定时巡检与用户当场签到可能同时命中同一条记录，
     * 只有一方能拿到 {@code rows=1}，违约次数绝不会被重复累加。
     * 也因此不需要额外加一个「已判违约」标记列。</p>
     *
     * @return true 表示本次调用确实把它改成了违约；false 表示已被别的路径处理过（或刚签到）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markViolated(Booking booking, LocalDateTime now) {
        if (booking == null || booking.getId() == null) {
            return false;
        }
        int rows = bookingMapper.update(null, new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, booking.getId())
                .eq(Booking::getStatus, BookingStatus.APPROVED)
                .isNull(Booking::getCheckInTime)
                .set(Booking::getStatus, BookingStatus.VIOLATED)
                .set(Booking::getUpdateTime, now));
        if (rows == 0) {
            return false;
        }

        MeetingRoom room = roomMapper.selectById(booking.getRoomId());
        String roomName = room == null ? null : room.getName();
        String subject = subjectText(booking);

        notificationService.push(booking.getUserId(), 2, "预约违约提醒",
                "您预约的「" + subject + "」未在 " + deadlineText(booking)
                        + " 前完成扫码签到，已记为违约，该会议室时段已释放给其他人预约。",
                booking.getId());

        SysUser user = userMapper.selectById(booking.getUserId());
        if (user != null) {
            // 接通原本无调用方的违约订阅消息：用户没到现场，站内通知大概率看不到，
            // 订阅消息才是唯一可能触达的渠道
            subscribeMessageService.sendViolation(user.getOpenid(), subject, roomName);
            processViolationCount(user, 1);
        }
        return true;
    }

    /**
     * 累计连续违约次数，达到阈值则禁止预约。
     *
     * <p>「连续」的含义：签到成功即清零（见 {@code BookingService.sign()}），
     * 封禁到期自动解封时也清零（见 {@code BookingScheduler.autoUnban()}）。</p>
     */
    public void processViolationCount(SysUser user, int addCount) {
        int count = (user.getViolationCount() == null ? 0 : user.getViolationCount()) + addCount;
        user.setViolationCount(count);
        userMapper.updateById(user);

        // 已被管理员永久禁用（status=0 / permanentBan=1）的用户不再叠加限时封禁，避免后台状态互相矛盾
        boolean disabled = user.getStatus() != null && user.getStatus() == 0;
        boolean permanent = user.getPermanentBan() != null && user.getPermanentBan() == 1;
        // 已经在限时封禁中的也不重复封禁，否则每违约一次就把解封时间往后推
        if (count >= banThreshold && user.getBannedUntil() == null && !disabled && !permanent) {
            LocalDateTime until = LocalDateTime.now().plusDays(banDays);
            user.setBannedUntil(until);
            // 标记来源为「连续违约自动」，用户被拦截时的提示才会说清原因
            user.setBanSource(BanSource.VIOLATION);
            userMapper.updateById(user);

            String untilStr = until.format(DT_FMT);
            notificationService.push(user.getId(), 3, "预约权限受限",
                    "您连续违约 " + count + " 次，已被禁止预约会议室，解封时间为 " + untilStr + "。", null);
            subscribeMessageService.sendBan(user.getOpenid(), untilStr);
            log.info("用户 {} 连续违约 {} 次，禁止预约至 {}", user.getId(), count, untilStr);
        }
    }

    /**
     * 批量判定（定时巡检用）。
     *
     * <p>单条独立事务：某条失败不影响其余，也不会把整批回滚。</p>
     *
     * @return 实际判定为违约的条数
     */
    public int markViolatedBatch(List<Booking> candidates, LocalDateTime now) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (Booking b : candidates) {
            try {
                if (markViolated(b, now)) {
                    done++;
                }
            } catch (Exception e) {
                log.error("标记预约 {} 违约失败", b.getId(), e);
            }
        }
        return done;
    }

    /** 会议主题兜底文案 */
    public static String subjectText(Booking booking) {
        return booking.getSubject() == null || booking.getSubject().trim().isEmpty()
                ? "会议室预约" : booking.getSubject();
    }

    /** 签到截止时刻的 HH:mm 文案，用于通知里给出可执行的行动时间 */
    public String deadlineText(Booking booking) {
        LocalDateTime deadline = signWindow.deadline(booking.getStartTime());
        return deadline == null ? "" : deadline.format(HM_FMT);
    }
}
