package com.meeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.BanSource;
import com.meeting.common.BookingStatus;
import com.meeting.common.BusinessException;
import com.meeting.common.LoginHelper;
import com.meeting.common.SignCodeUtil;
import com.meeting.common.SignWindow;
import com.meeting.dto.BookingCreateDTO;
import com.meeting.dto.BookingSlotVO;
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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingMapper bookingMapper;
    private final MeetingRoomMapper roomMapper;
    private final SysUserMapper userMapper;
    /** 签到窗口的唯一定义（提前量 / 迟到量），与定时任务共用 */
    private final SignWindow signWindow;
    /** 违约判定独立成 Bean：超时签到时需要「先判违约、再抛异常」，必须走独立事务 */
    private final ViolationService violationService;
    /** 管理端代取消后要给用户一个站内通知，否则用户只会看到「预约莫名消失了」 */
    private final NotificationService notificationService;

    @Value("${meeting.max-advance-days}")
    private int maxAdvanceDays;

    @Value("${meeting.max-duration-hours}")
    private int maxDurationHours;

    @Value("${meeting.cancel-before-hours}")
    private int cancelBeforeHours;

    @Value("${meeting.slot-minutes:15}")
    private int slotMinutes;

    @Value("${meeting.gap-minutes:15}")
    private int gapMinutes;

    /** 分页单页上限（P2-16） */
    private static final int MAX_PAGE_SIZE = 100;
    /** 驳回原因最大长度，与 booking.reject_reason varchar(255) 对齐 */
    private static final int MAX_REJECT_REASON_LENGTH = 255;
    /** 时间文案统一用 HH:mm，与小程序前端 substring(11,16) 的效果一致 */
    private static final DateTimeFormatter HM_FMT = DateTimeFormatter.ofPattern("HH:mm");
    /** 关键字筛选时最多带多少个用户 ID 进 IN 条件 */
    private static final int MAX_KEYWORD_USER_IDS = 200;

    /** 管理端签到状态：0 无需签到 / 1 未签到 / 2 已签到 / 3 超时未签到 / 4 已违约 */
    public static final int SIGN_STATE_NONE = 0;
    public static final int SIGN_STATE_WAITING = 1;
    public static final int SIGN_STATE_DONE = 2;
    public static final int SIGN_STATE_TIMEOUT = 3;
    public static final int SIGN_STATE_VIOLATED = 4;

    /**
     * 创建预约
     */
    @Transactional(rollbackFor = Exception.class)
    public Booking create(BookingCreateDTO dto) {
        Long userId = LoginHelper.userId();
        LocalDateTime now = LocalDateTime.now();

        // 封禁校验：被禁止预约期间无法新建预约
        assertBookable(userId, now);

        // P2-11：先对会议室加行锁，把「冲突检测 → 插入」串行化，避免并发重复预约
        MeetingRoom room = requireBookableRoom(dto.getRoomId());
        String subject = normalizeSubject(dto.getSubject());
        validateSlot(room, dto.getStartTime(), dto.getEndTime(), null);

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setRoomId(dto.getRoomId());
        booking.setStartTime(dto.getStartTime());
        booking.setEndTime(dto.getEndTime());
        booking.setSubject(subject);
        // 所有预约统一进入人工审核
        booking.setStatus(0);
        bookingMapper.insert(booking);

        return booking;
    }

    /**
     * 修改预约（用户端）。
     *
     * <p>业务规则：</p>
     * <ul>
     *   <li>只有「待审批 / 已通过」可改，且必须尚未签到、距离开始还有
     *       {@code cancel-before-hours} 小时以上（与取消同一门槛，
     *       保证管理员重新审核时还有反应时间）。</li>
     *   <li>只改主题 → 状态不变；改了时间或会议室 → <b>已通过的预约退回待审批</b>。
     *       这条是刻意的：审核针对的是「某人在某时段占用某房间」，
     *       要素变了就应该重新确认，否则用户可以先约个空闲时段通过审核，
     *       再改成黄金时段绕过审批。</li>
     *   <li>退回待审批时必须清空 approveTime / approverId / rejectReason，
     *       否则管理端会出现「待审批却带着审批时间」的矛盾展示。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public Booking update(Long id, BookingCreateDTO dto) {
        Long userId = LoginHelper.userId();
        Booking booking = bookingMapper.selectById(id);
        if (booking == null || !booking.getUserId().equals(userId)) {
            throw new BusinessException(400, "预约不存在");
        }

        // 封禁校验必须与 create 共用同一套：只拦新建、放行修改，等于封禁可以靠「改时间」绕过。
        // 改完时间会重新进入审批流，被禁用户照样能占住黄金时段。
        assertBookable(userId, LocalDateTime.now());

        int from = booking.getStatus() == null ? -1 : booking.getStatus();
        if (from != BookingStatus.PENDING && from != BookingStatus.APPROVED) {
            throw new BusinessException(400, "该预约当前状态不支持修改");
        }
        if (booking.getCheckInTime() != null) {
            throw new BusinessException(400, "该预约已签到，无法修改");
        }

        LocalDateTime now = LocalDateTime.now();
        if (booking.getStartTime() == null || !booking.getStartTime().isAfter(now)) {
            throw new BusinessException(400, "会议已开始，无法修改");
        }
        if (booking.getStartTime().isBefore(now.plusHours(cancelBeforeHours))) {
            throw new BusinessException(400, "开始前 " + cancelBeforeHours
                    + " 小时内不可修改，如有变动请取消后重新预约");
        }

        // 与新建走完全相同的校验链（含加锁与冲突检测），避免两套规则漂移
        MeetingRoom room = requireBookableRoom(dto.getRoomId());
        String subject = normalizeSubject(dto.getSubject());
        validateSlot(room, dto.getStartTime(), dto.getEndTime(), id);

        boolean slotChanged = !dto.getRoomId().equals(booking.getRoomId())
                || !dto.getStartTime().equals(booking.getStartTime())
                || !dto.getEndTime().equals(booking.getEndTime());
        boolean backToPending = slotChanged && from == BookingStatus.APPROVED;

        LambdaUpdateWrapper<Booking> wrapper = new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, id)
                // 条件更新：eq(status = 变更前状态)，防止与管理员审批撞车时互相覆盖
                .eq(Booking::getStatus, from)
                .set(Booking::getRoomId, dto.getRoomId())
                .set(Booking::getStartTime, dto.getStartTime())
                .set(Booking::getEndTime, dto.getEndTime())
                .set(Booking::getSubject, subject)
                .set(Booking::getStatus, backToPending ? BookingStatus.PENDING : from)
                .set(Booking::getUpdateTime, now);
        if (slotChanged) {
            // 时间变了，之前发出的临近提醒作废，让提醒任务对新时间重新命中
            wrapper.set(Booking::getRemindTime, null);
        }
        if (backToPending) {
            wrapper.set(Booking::getApproverId, null)
                    .set(Booking::getApproveTime, null)
                    .set(Booking::getRejectReason, null);
        }
        if (bookingMapper.update(null, wrapper) == 0) {
            throw new BusinessException(400, "预约状态已变化，请刷新后重试");
        }

        log.info("用户 {} 修改预约 {}：{} → {}，状态 {} → {}",
                userId, id, booking.getStartTime(), dto.getStartTime(), from,
                backToPending ? BookingStatus.PENDING : from);

        // 回填返回值，小程序不必再拉一次列表就能刷新卡片
        Booking latest = bookingMapper.selectById(id);
        List<Booking> one = new ArrayList<>();
        one.add(latest);
        fillRoomName(one);
        fillSignInfo(one);
        fillUserActions(one);
        return latest;
    }

    /**
     * 单条预约详情（编辑页预填用）。
     *
     * <p>只返回本人的预约；越权与不存在合并为同一句提示，不泄露"这条预约是否存在"。</p>
     */
    public Booking detail(Long id) {
        Long userId = LoginHelper.userId();
        Booking booking = bookingMapper.selectById(id);
        if (booking == null || !booking.getUserId().equals(userId)) {
            throw new BusinessException(400, "预约不存在");
        }
        List<Booking> one = new ArrayList<>();
        one.add(booking);
        fillRoomName(one);
        fillSignInfo(one);
        fillUserActions(one);
        return booking;
    }

    /**
     * 我的预约列表
     */
    public List<Booking> myBookings(Integer status) {
        Long userId = LoginHelper.userId();
        LambdaQueryWrapper<Booking> wrapper = new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .orderByDesc(Booking::getStartTime);
        if (status != null) {
            wrapper.eq(Booking::getStatus, status);
        }
        List<Booking> list = bookingMapper.selectList(wrapper);
        fillRoomName(list);
        // 签到按钮显隐与签到窗口边界值由后端算好下发，小程序不再硬编码 15 分钟
        fillSignInfo(list);
        // 取消 / 修改按钮的可用性同样由后端下发，避免手机时间被改动后按钮与接口打架
        fillUserActions(list);
        return list;
    }

    /**
     * 取消预约
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        Long userId = LoginHelper.userId();
        Booking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw new BusinessException(400, "预约不存在");
        }
        if (!booking.getUserId().equals(userId)) {
            throw new BusinessException(400, "只能取消自己的预约");
        }
        if (booking.getStatus() == 3) {
            throw new BusinessException(400, "该预约已取消");
        }
        if (booking.getStatus() == 2 || booking.getStatus() == 4) {
            throw new BusinessException(400, "该预约已结束，无法取消");
        }

        // 开始前 cancelBeforeHours 小时内不可取消
        LocalDateTime now = LocalDateTime.now();
        if (booking.getStartTime().isBefore(now.plusHours(cancelBeforeHours))) {
            throw new BusinessException(400, "开始前 " + cancelBeforeHours + " 小时内不可取消");
        }

        booking.setStatus(3);
        bookingMapper.updateById(booking);
    }

    /**
     * 扫码签到。
     *
     * <p>签到窗口 = {@code [会议开始前 sign-window-minutes, 会议开始后 sign-late-minutes]}，
     * 超过截止时刻即判违约、不可再签。</p>
     *
     * <p><b>本方法刻意不加 {@code @Transactional}</b>：超时分支需要「先把预约置为违约、
     * 再抛业务异常告知用户」。若加了事务，抛出的运行时异常会把刚写入的违约标记一起回滚，
     * 用户看到「已超时」但状态仍是「已通过」，要等定时任务下一轮才纠正。
     * 违约动作放在 {@link ViolationService}（自带事务），跨 Bean 调用时独立提交。</p>
     *
     * <p>校验顺序刻意把「物理问题」（扫错门、码无效）排在「时间问题」之前 ——
     * 用户站在错误的门口时应当立刻被告知走错了，而不是先等一个"签到未开始"的提示。</p>
     *
     * @param scanCode 扫码原文，格式见 {@link SignCodeUtil}
     */
    public Booking sign(Long id, String scanCode) {
        Long userId = LoginHelper.userId();
        Booking booking = bookingMapper.selectById(id);
        if (booking == null || !booking.getUserId().equals(userId)) {
            // 合并成同一句：不泄露"这条预约是否存在"
            throw new BusinessException(400, "预约不存在");
        }

        int status = booking.getStatus() == null ? -1 : booking.getStatus();
        if (status == BookingStatus.VIOLATED) {
            throw new BusinessException(400, "该预约已超时未签到，已判定违约，会议室时段已释放给其他人");
        }
        if (status == BookingStatus.CANCELED) {
            throw new BusinessException(400, "预约已取消，无需签到");
        }
        if (status == BookingStatus.REJECTED) {
            throw new BusinessException(400, "预约已被驳回，无法签到");
        }
        if (status == BookingStatus.PENDING) {
            throw new BusinessException(400, "预约待审批，审批通过后才能签到");
        }
        if (status != BookingStatus.APPROVED) {
            throw new BusinessException(400, "预约状态异常，无法签到");
        }
        if (booking.getCheckInTime() != null) {
            throw new BusinessException(400, "您已于 " + booking.getCheckInTime().format(HM_FMT) + " 完成签到，请勿重复操作");
        }

        // ---- 扫码内容校验 ----
        SignCodeUtil.Parsed parsed = SignCodeUtil.parse(scanCode);
        if (parsed == null) {
            throw new BusinessException(400, "签到码无法识别，请扫描会议室门前的签到二维码");
        }
        // 只用 sign_code 查库；二维码里的 roomId 仅用于一致性校验
        MeetingRoom room = roomMapper.selectBySignCode(parsed.getCode());
        if (room == null || room.getStatus() == null || room.getStatus() != 1) {
            throw new BusinessException(400, "签到码无效或已失效，请联系管理员重新生成");
        }
        if (room.getId() == null || room.getId() != parsed.getRoomId()) {
            throw new BusinessException(400, "签到码与会议室不匹配，请重新扫码");
        }
        if (!room.getId().equals(booking.getRoomId())) {
            MeetingRoom bookedRoom = roomMapper.selectById(booking.getRoomId());
            String name = bookedRoom == null ? "预约的会议室" : "「" + bookedRoom.getName() + "」";
            throw new BusinessException(400, "扫错会议室了，请到" + name + "门前扫码签到");
        }

        // ---- 时间窗口校验 ----
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime openAt = signWindow.openAt(booking.getStartTime());
        if (now.isBefore(openAt)) {
            throw new BusinessException(400, "签到尚未开始，会议开始前 " + signWindow.getBeforeMinutes()
                    + " 分钟（" + openAt.format(HM_FMT) + "）起可签到");
        }
        if (signWindow.isTimeout(booking.getStartTime(), now)) {
            // 先落违约（独立事务，不会被下面的异常回滚），再告知用户结果
            violationService.markViolated(booking, now);
            LocalDateTime deadline = signWindow.deadline(booking.getStartTime());
            throw new BusinessException(400, "已超过签到截止时间 " + deadline.format(HM_FMT)
                    + "，本次预约已判定违约，会议室时段已释放");
        }

        // ---- 写入：条件更新，与 approve() 同一套并发防护 ----
        int rows = bookingMapper.update(null, new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, id)
                .eq(Booking::getStatus, BookingStatus.APPROVED)
                .isNull(Booking::getCheckInTime)
                .set(Booking::getCheckInTime, now)
                .set(Booking::getUpdateTime, now));
        if (rows == 0) {
            // 并发场景：另一请求刚签完，或定时任务刚判违约。重查一次给出准确文案
            throw new BusinessException(400, resolveSignConflict(bookingMapper.selectById(id)));
        }

        // 履约成功，打断「连续违约」
        SysUser user = userMapper.selectById(userId);
        if (user != null && user.getViolationCount() != null && user.getViolationCount() > 0) {
            user.setViolationCount(0);
            userMapper.updateById(user);
        }

        // 回填返回值，前端不必再拉一次列表就能显示「已签到 HH:mm」
        booking.setCheckInTime(now);
        booking.setRoomName(room.getName());
        booking.setSignOpenAt(openAt);
        booking.setSignDeadline(signWindow.deadline(booking.getStartTime()));
        booking.setCanSign(false);
        booking.setSignWindowOpen(false);
        booking.setCanEdit(false);
        booking.setCanCancel(false);
        return booking;
    }

    /**
     * 条件更新影响 0 行时的准确文案。
     *
     * <p>比笼统的"状态已变化，请刷新"更有用：用户点了签到，需要知道到底是
     * 已经签成功了（弱网重试），还是被判违约了。</p>
     */
    private String resolveSignConflict(Booking latest) {
        if (latest == null) {
            return "预约不存在";
        }
        if (latest.getCheckInTime() != null) {
            return "您已于 " + latest.getCheckInTime().format(HM_FMT) + " 完成签到，请勿重复操作";
        }
        if (latest.getStatus() != null && latest.getStatus() == BookingStatus.VIOLATED) {
            return "该预约已超时未签到，已判定违约，会议室时段已释放给其他人";
        }
        return "预约状态已变化，请刷新后重试";
    }

    /**
     * 查询会议室某日已占用时段
     *
     * <p>返回 {@link BookingSlotVO} 而非实体：只暴露时间区间与状态，
     * 不含预约人、会议主题、审批与签到信息。</p>
     *
     * @param excludeId 要排除的预约 id（编辑预约时传自身，否则自己的时段会显示为「已占用」）
     */
    public List<BookingSlotVO> dayBookings(Long roomId, LocalDate date, Long excludeId) {
        if (roomId == null) {
            throw new BusinessException(400, "会议室 ID 不能为空");
        }
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        return bookingMapper.listDaySlots(roomId, dayStart, dayEnd, excludeId);
    }

    /**
     * 管理端：分页查询预约
     *
     * @param keyword 关键字：匹配会议主题，或预约人昵称/手机号（三个字段任一命中即可）。
     *                只按状态筛选时，管理员要在一堆「待审批」里找某个人的某场会只能靠翻页，
     *                有了关键字才能直接定位。
     */
    public Page<Booking> page(Integer status, Long roomId, Integer signState, String keyword,
                              int page, int size) {
        // P2-16：分页参数兜底，防止 size=100000 这类请求把整表读进内存
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Booking> wrapper = new LambdaQueryWrapper<Booking>()
                .orderByDesc(Booking::getCreateTime);
        if (status != null) {
            wrapper.eq(Booking::getStatus, status);
        }
        if (roomId != null) {
            wrapper.eq(Booking::getRoomId, roomId);
        }
        if (signState != null) {
            applySignStateFilter(wrapper, signState, LocalDateTime.now());
        }
        applyKeywordFilter(wrapper, keyword);
        Page<Booking> result = bookingMapper.selectPage(new Page<>(page, size), wrapper);
        // P2-6：列表补会议室名与预约人，审核时不必再对照 ID
        fillRoomName(result.getRecords());
        fillUserName(result.getRecords());
        // 审核动作可用性由后端计算后下发，前端只负责按标记显隐按钮
        fillActions(result.getRecords());
        fillSignState(result.getRecords());
        return result;
    }

    /**
     * 管理端关键字筛选：会议主题 或 预约人（昵称/手机号）。
     *
     * <p>不用 SQL 字符串拼接 {@code inSql}：用户输入一旦进入拼接就可能变成注入口子。
     * 这里先按关键字查一遍用户表拿到 id 列表，再用参数化 {@code IN}，
     * 命中用户过多时截断（关键字本来就是为了精确定位，200 个候选已经足够）。</p>
     */
    private void applyKeywordFilter(LambdaQueryWrapper<Booking> wrapper, String keyword) {
        if (keyword == null) {
            return;
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return;
        }
        // 关键字里的 % _ 是 LIKE 通配符，用户搜 "a_b" 不该匹配到 "axb"
        String escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Long> userIds = new ArrayList<>();
        for (SysUser u : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .and(w -> w.like(SysUser::getNickname, escaped)
                        .or().like(SysUser::getPhone, escaped))
                .last("LIMIT " + MAX_KEYWORD_USER_IDS))) {
            userIds.add(u.getId());
        }
        wrapper.and(w -> {
            w.like(Booking::getSubject, escaped);
            if (!userIds.isEmpty()) {
                w.or().in(Booking::getUserId, userIds);
            }
        });
    }

    /**
     * 管理端「签到状态」筛选条件。
     *
     * <p>与 {@link #fillSignState(List)} 的口径必须严格一致，否则会出现
     * 「筛出来 3 条，列表里显示的却是未签到」这种自相矛盾的界面。</p>
     */
    private void applySignStateFilter(LambdaQueryWrapper<Booking> wrapper, int signState, LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(signWindow.getLateMinutes());
        switch (signState) {
            case SIGN_STATE_NONE:
                // 无需签到：待审批 / 已驳回 / 已取消
                wrapper.in(Booking::getStatus, BookingStatus.PENDING, BookingStatus.REJECTED, BookingStatus.CANCELED);
                break;
            case SIGN_STATE_WAITING:
                wrapper.eq(Booking::getStatus, BookingStatus.APPROVED)
                        .isNull(Booking::getCheckInTime)
                        .ge(Booking::getStartTime, cutoff);
                break;
            case SIGN_STATE_DONE:
                wrapper.isNotNull(Booking::getCheckInTime);
                break;
            case SIGN_STATE_TIMEOUT:
                // 已过签到截止、但定时任务还没跑到（最多存在 60 秒的中间态）
                wrapper.eq(Booking::getStatus, BookingStatus.APPROVED)
                        .isNull(Booking::getCheckInTime)
                        .lt(Booking::getStartTime, cutoff);
                break;
            case SIGN_STATE_VIOLATED:
                wrapper.eq(Booking::getStatus, BookingStatus.VIOLATED);
                break;
            default:
                // 未知值当作不筛选，避免前端传错参数就查不到任何数据
                break;
        }
    }

    /**
     * 管理端：审批通过（同时支持「已驳回 → 已通过」的改判）
     *
     * <p>允许的起始状态：0 待审批、2 已驳回。已驳回的记录在被驳回期间，
     * 该时段可能已被其他预约占用，因此必须重跑一次冲突检测后再放行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id) {
        Long adminId = LoginHelper.adminId();
        Booking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw new BusinessException(400, "预约不存在");
        }
        int from = booking.getStatus() == null ? -1 : booking.getStatus();
        if (from != BookingStatus.PENDING && from != BookingStatus.REJECTED) {
            throw new BusinessException(400, "该预约当前状态不支持通过");
        }
        LocalDateTime now = LocalDateTime.now();
        // 会议一旦开始就锁定审核结果，避免进行中的会议被改成「已通过」导致签到/违约判定错乱
        if (booking.getStartTime() == null || !booking.getStartTime().isAfter(now)) {
            throw new BusinessException(400, "会议已开始，无法变更为已通过");
        }
        // P0-3：二次冲突检测要排除当前预约自身，否则必然与自己的时段冲突
        int conflict = bookingMapper.countConflict(
                booking.getRoomId(), booking.getStartTime(), booking.getEndTime(), gapMinutes, booking.getId());
        if (conflict > 0) {
            throw new BusinessException(400, "该时段已被其他预约占用，无法通过");
        }
        // 条件更新：eq(status = 变更前状态)，防止两个管理员同时改判互相覆盖
        LambdaUpdateWrapper<Booking> wrapper = new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, id)
                .eq(Booking::getStatus, from)
                .set(Booking::getStatus, BookingStatus.APPROVED)
                // 改判必须显式清空驳回原因：updateById 走 NOT_NULL 策略，set null 不会写库
                .set(Booking::getRejectReason, null)
                .set(Booking::getApproverId, adminId)
                .set(Booking::getApproveTime, now)
                .set(Booking::getUpdateTime, now);
        if (from == BookingStatus.REJECTED) {
            // 驳回期间可能已发过临近提醒，清空标记让提醒任务重新命中
            wrapper.set(Booking::getRemindTime, null);
        }
        if (bookingMapper.update(null, wrapper) == 0) {
            throw new BusinessException(400, "预约状态已变化，请刷新后重试");
        }
        log.info("管理员 {} 将预约 {} 由状态 {} 改为已通过", adminId, id, from);
    }

    /**
     * 管理端：审批驳回（同时支持「已通过 → 已驳回」的改判）
     *
     * <p>待审批状态随时可驳回（含已开始但一直没人处理的历史记录，否则会永久卡在待审批）；
     * 已通过的预约只允许在会议开始前、且用户尚未签到时改判。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        Long adminId = LoginHelper.adminId();
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException(400, "请填写驳回原因");
        }
        String trimmedReason = reason.trim();
        // P2-15：超长原因写入 varchar(255) 会抛 SQL 异常变成 500，这里先拦成 400
        if (trimmedReason.length() > MAX_REJECT_REASON_LENGTH) {
            throw new BusinessException(400, "驳回原因不能超过 " + MAX_REJECT_REASON_LENGTH + " 个字符");
        }
        Booking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw new BusinessException(400, "预约不存在");
        }
        int from = booking.getStatus() == null ? -1 : booking.getStatus();
        if (from != BookingStatus.PENDING && from != BookingStatus.APPROVED) {
            throw new BusinessException(400, "该预约当前状态不支持驳回");
        }
        LocalDateTime now = LocalDateTime.now();
        if (from == BookingStatus.APPROVED) {
            if (booking.getStartTime() == null || !booking.getStartTime().isAfter(now)) {
                throw new BusinessException(400, "会议已开始，无法变更为已驳回");
            }
            // 已签到是履约的客观证据，驳回会造出「已签到却已驳回」的矛盾数据
            if (booking.getCheckInTime() != null) {
                throw new BusinessException(400, "该预约已签到，不能驳回");
            }
        }
        LambdaUpdateWrapper<Booking> wrapper = new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, id)
                .eq(Booking::getStatus, from)
                .set(Booking::getStatus, BookingStatus.REJECTED)
                .set(Booking::getRejectReason, trimmedReason)
                .set(Booking::getApproverId, adminId)
                .set(Booking::getApproveTime, now)
                .set(Booking::getUpdateTime, now);
        if (bookingMapper.update(null, wrapper) == 0) {
            throw new BusinessException(400, "预约状态已变化，请刷新后重试");
        }
        log.info("管理员 {} 将预约 {} 由状态 {} 改为已驳回，原因：{}", adminId, id, from, trimmedReason);
    }

    /**
     * 管理端：代用户取消预约（释放时段）。
     *
     * <p>为什么必须有这个接口：用户侧「开始前 2 小时不可取消」是硬规则，
     * 但会议室被误占、临时调整场地这类事天天发生。之前管理员只能通过或驳回，
     * 面对一条「已通过、马上开始」的预约完全无计可施，时段就那么锁死了。</p>
     *
     * <p>约束：只对「待审批 / 已通过且未签到、未开始」的记录生效。
     * 已开始或已签到的记录不允许取消 —— 否则会议室会被释放，
     * 同一时段可能被第二个人预约，现场直接撞车。</p>
     *
     * @param reason 取消原因（可选，会写进用户通知里，便于解释）
     */
    @Transactional(rollbackFor = Exception.class)
    public void adminCancel(Long id, String reason) {
        Long adminId = LoginHelper.adminId();
        Booking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw new BusinessException(404, "预约不存在");
        }
        int from = booking.getStatus() == null ? -1 : booking.getStatus();
        if (from != BookingStatus.PENDING && from != BookingStatus.APPROVED) {
            throw new BusinessException(400, "该预约当前状态不支持取消");
        }
        LocalDateTime now = LocalDateTime.now();
        if (booking.getStartTime() == null || !booking.getStartTime().isAfter(now)) {
            throw new BusinessException(400, "会议已开始，无法取消；如会议室被占用请现场协调");
        }
        if (booking.getCheckInTime() != null) {
            throw new BusinessException(400, "该预约已签到，取消会产生「已签到却已取消」的矛盾记录");
        }

        LambdaUpdateWrapper<Booking> wrapper = new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, id)
                .eq(Booking::getStatus, from)
                .set(Booking::getStatus, BookingStatus.CANCELED)
                // 留痕：谁取消的。管理员取消与用户自己取消必须能区分
                .set(Booking::getCancelBy, adminId)
                .set(Booking::getUpdateTime, now);
        if (bookingMapper.update(null, wrapper) == 0) {
            throw new BusinessException(400, "预约状态已变化，请刷新后重试");
        }

        String reasonText = reason == null ? "" : reason.trim();
        String content = "管理员已取消您预约的「" + subjectText(booking) + "」（"
                + booking.getStartTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " - "
                + booking.getEndTime().format(HM_FMT) + "）。"
                + (reasonText.isEmpty() ? "如有疑问请联系管理员。" : "原因：" + reasonText);
        notificationService.push(booking.getUserId(), 4, "预约已被取消", content, booking.getId());

        log.info("管理员 {} 取消预约 {}（原状态 {}），原因：{}", adminId, id, from,
                reasonText.isEmpty() ? "未填写" : reasonText);
    }

    // ===== 内部辅助 =====

    /**
     * 封禁校验：create 与 update 共用同一份。
     *
     * <p>提示文案按封禁来源区分：连续违约自动封禁才说「因连续违约」，
     * 管理员手动封禁说「管理员已暂停您的预约权限」，否则等于替管理员背锅。</p>
     */
    private void assertBookable(Long userId, LocalDateTime now) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        // 永久禁用：登录都过不去，理论上到不了这里；防御性保留（token 未过期时可能命中）
        if (user.getPermanentBan() != null && user.getPermanentBan() == 1) {
            throw new BusinessException(400, "您的账号已被管理员禁用，无法预约，如有疑问请联系管理员");
        }
        if (user.getBannedUntil() == null || !user.getBannedUntil().isAfter(now)) {
            return;
        }
        String until = user.getBannedUntil().toLocalDate().toString();
        Integer source = user.getBanSource();
        if (source != null && source == BanSource.MANUAL) {
            throw new BusinessException(400, "您的预约权限已被管理员暂停，恢复时间：" + until
                    + "，如有疑问请联系管理员");
        }
        throw new BusinessException(400, "您因连续违约已被禁止预约，解封时间：" + until);
    }

    private String subjectText(Booking booking) {
        return ViolationService.subjectText(booking);
    }

    /**
     * 加行锁并取回一个可预约的会议室。
     *
     * <p>锁必须在同一事务内先拿：把「冲突检测 → 插入/更新」串行化，
     * 否则并发下两条请求会同时通过冲突检测（P2-11）。</p>
     */
    private MeetingRoom requireBookableRoom(Long roomId) {
        roomMapper.lockById(roomId);
        MeetingRoom room = roomMapper.selectById(roomId);
        if (room == null || room.getStatus() == null || room.getStatus() == 0) {
            throw new BusinessException(400, "会议室不存在或已停用");
        }
        return room;
    }

    /** 会议主题归一化：trim 兜底，防纯空格绕过 @NotBlank */
    private String normalizeSubject(String raw) {
        if (raw == null) {
            throw new BusinessException(400, "会议主题不能为空");
        }
        String subject = raw.trim();
        if (subject.isEmpty()) {
            throw new BusinessException(400, "会议主题不能为空");
        }
        return subject;
    }

    /**
     * 时段全量校验：时间合法性 → 开放时间 → 15 分钟粒度 → 冲突。
     *
     * <p>新建与修改共用这一份，避免「新建拦住了、修改放过去了」这类规则漂移漏洞。</p>
     *
     * @param excludeId 冲突检测要排除的预约 id：修改时传自身，新建传 null
     */
    private void validateSlot(MeetingRoom room, LocalDateTime start, LocalDateTime end, Long excludeId) {
        if (start == null || end == null) {
            throw new BusinessException(400, "预约时间不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        if (start.isBefore(now)) {
            throw new BusinessException(400, "预约时间不能早于当前时间");
        }
        if (!end.isAfter(start)) {
            throw new BusinessException(400, "结束时间必须晚于开始时间");
        }

        // P1-5：禁止跨天预约
        if (!start.toLocalDate().equals(end.toLocalDate())) {
            throw new BusinessException(400, "预约必须在同一天内完成，不支持跨天");
        }

        // 校验提前预约范围
        if (start.toLocalDate().isAfter(LocalDate.now().plusDays(maxAdvanceDays))) {
            throw new BusinessException(400, "最多可提前 " + maxAdvanceDays + " 天预约");
        }

        // P1-6：按分钟比较，避免 Duration.toHours() 截断小数（4.5 小时被当成 4 小时放行）
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes > maxDurationHours * 60L) {
            throw new BusinessException(400, "单次预约最长 " + maxDurationHours + " 小时");
        }

        // 校验开放时间（开始不得早于开门，结束不得晚于打烊）
        LocalTime startTime = start.toLocalTime();
        LocalTime endTime = end.toLocalTime();
        if (room.getOpenTime() != null && startTime.isBefore(room.getOpenTime())) {
            throw new BusinessException(400, "会议室开放时间为 " + room.getOpenTime() + " 之后");
        }
        if (room.getCloseTime() != null && endTime.isAfter(room.getCloseTime())) {
            throw new BusinessException(400, "会议室开放时间为 " + room.getCloseTime() + " 之前");
        }

        // 15 分钟粒度对齐：起止分钟的秒必须为 0，分钟必须是 slotMinutes 的整数倍
        if (start.getSecond() != 0 || start.getMinute() % slotMinutes != 0
                || end.getSecond() != 0 || end.getMinute() % slotMinutes != 0) {
            throw new BusinessException(400, "预约时间必须为 " + slotMinutes + " 分钟整点");
        }
        // 时长必须是 slotMinutes 的整数倍
        if (minutes % slotMinutes != 0) {
            throw new BusinessException(400, "预约时长必须为 " + slotMinutes + " 分钟的整数倍");
        }

        // 冲突检测：新建无记录可排除，修改需排除自身，否则必然与自己冲突
        int conflict = bookingMapper.countConflict(room.getId(), start, end, gapMinutes, excludeId);
        if (conflict > 0) {
            throw new BusinessException(400, "该时间段已被预约或与相邻预约间隔不足，请选择其他时间");
        }
    }

    /**
     * 计算用户端的「取消 / 修改」按钮可用性。
     *
     * <p>两者共用同一时间门槛（开始前 {@code cancel-before-hours} 小时），
     * 所以用一份规则算两个标记：临近开始既不该取消、也不该改 ——
     * 改了要重新审核，管理员已经来不及处理。</p>
     */
    private void fillUserActions(List<Booking> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().plusHours(cancelBeforeHours);
        for (Booking b : list) {
            int s = b.getStatus() == null ? -1 : b.getStatus();
            boolean active = (s == BookingStatus.PENDING || s == BookingStatus.APPROVED)
                    && b.getCheckInTime() == null
                    && b.getStartTime() != null
                    && b.getStartTime().isAfter(cutoff);
            b.setCanCancel(active);
            b.setCanEdit(active);
        }
    }

    /**
     * 计算每一行可用的审核动作。
     *
     * <p>规则只在这里维护一份，前端按标记显隐按钮，避免前后端各写一套时间判断而漂移。
     * 注意这里不含时段冲突检测（需逐行查库，N+1 不可接受），冲突由点击时的
     * {@link #approve(Long)} 兜底拒绝。</p>
     */
    private void fillActions(List<Booking> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        // 同一批共用一个时刻，避免列表内前后行的判断基准不一致
        LocalDateTime now = LocalDateTime.now();
        for (Booking b : list) {
            int s = b.getStatus() == null ? -1 : b.getStatus();
            boolean beforeStart = b.getStartTime() != null && b.getStartTime().isAfter(now);
            boolean canApprove = (s == BookingStatus.PENDING || s == BookingStatus.REJECTED) && beforeStart;
            boolean canReject = s == BookingStatus.PENDING
                    || (s == BookingStatus.APPROVED && beforeStart && b.getCheckInTime() == null);
            // 管理端取消：兜底通道，不受用户侧「开始前 2 小时」限制，但会议开始或已签到后不允许
            boolean canCancelByAdmin = (s == BookingStatus.PENDING || s == BookingStatus.APPROVED)
                    && beforeStart && b.getCheckInTime() == null;
            b.setCanApprove(canApprove);
            b.setCanReject(canReject);
            b.setCanCancelByAdmin(canCancelByAdmin);
        }
    }

    /**
     * 计算每条预约的签到入口可用性与窗口边界，下发给小程序。
     *
     * <p><b>canSign 是「入口」而不是「此刻能不能签」</b>：只要审批通过、还没签到、
     * 还没过签到截止，就为 true，按钮从审批通过那一刻起一直挂在卡片上。
     * 早先的写法要求窗口已开启才为 true，用户审批通过后看不到按钮，
     * 会以为这场会不用签到，等到想起来时早已过了截止被判违约。</p>
     *
     * <p>「此刻能不能真的扫」由 {@code signWindowOpen} 表达，小程序点击时
     * 用它决定是调起扫码还是提示「几点开始」。两个标记都来自后端配置，
     * 前端不存在第二份规则。</p>
     */
    private void fillSignInfo(List<Booking> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        // 同一批共用一个时刻，避免列表内前后行的判断基准不一致
        LocalDateTime now = LocalDateTime.now();
        for (Booking b : list) {
            LocalDateTime start = b.getStartTime();
            if (start == null) {
                b.setCanSign(false);
                b.setSignWindowOpen(false);
                continue;
            }
            b.setSignOpenAt(signWindow.openAt(start));
            b.setSignDeadline(signWindow.deadline(start));
            boolean active = b.getStatus() != null
                    && b.getStatus() == BookingStatus.APPROVED
                    && b.getCheckInTime() == null
                    && !signWindow.isTimeout(start, now);
            b.setCanSign(active);
            b.setSignWindowOpen(active && signWindow.isOpen(start, now));
        }
    }

    /**
     * 计算管理端「签到状态」，口径与 {@link #applySignStateFilter} 严格一致。
     */
    private void fillSignState(List<Booking> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Booking b : list) {
            b.setSignState(resolveSignState(b, now));
        }
    }

    private int resolveSignState(Booking b, LocalDateTime now) {
        int s = b.getStatus() == null ? -1 : b.getStatus();
        if (s == BookingStatus.VIOLATED) {
            return SIGN_STATE_VIOLATED;
        }
        if (b.getCheckInTime() != null) {
            return SIGN_STATE_DONE;
        }
        if (s != BookingStatus.APPROVED) {
            // 待审批 / 已驳回 / 已取消：都谈不上签到
            return SIGN_STATE_NONE;
        }
        if (signWindow.isTimeout(b.getStartTime(), now)) {
            return SIGN_STATE_TIMEOUT;
        }
        // 已通过、未签到、尚未超时：无论窗口是否已开，都算「未签到」。
        // 不区分「还没到签到时间」，因为管理端关心的是「这条还没签」而不是「还不该签」。
        return SIGN_STATE_WAITING;
    }

    private void fillRoomName(List<Booking> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (Booking b : list) {
            if (b.getRoomId() != null) {
                ids.add(b.getRoomId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names = new HashMap<>();
        for (MeetingRoom room : roomMapper.selectBatchIds(ids)) {
            names.put(room.getId(), room.getName());
        }
        for (Booking b : list) {
            b.setRoomName(names.get(b.getRoomId()));
        }
    }

    private void fillUserName(List<Booking> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (Booking b : list) {
            if (b.getUserId() != null) {
                ids.add(b.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names = new HashMap<>();
        List<SysUser> users = new ArrayList<>(userMapper.selectBatchIds(ids));
        for (SysUser u : users) {
            String name = u.getNickname();
            if (name == null || name.trim().isEmpty()) {
                name = u.getPhone() != null ? u.getPhone() : ("用户" + u.getId());
            }
            names.put(u.getId(), name);
        }
        for (Booking b : list) {
            b.setUserName(names.get(b.getUserId()));
        }
    }
}
