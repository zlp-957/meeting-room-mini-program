package com.meeting.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.BanSource;
import com.meeting.common.BookingStatus;
import com.meeting.common.BusinessException;
import com.meeting.common.LoginHelper;
import com.meeting.dto.UserDetailVO;
import com.meeting.dto.UserListVO;
import com.meeting.entity.Booking;
import com.meeting.entity.SysUser;
import com.meeting.mapper.BookingMapper;
import com.meeting.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户管理服务（管理端）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 用户详情里「最近预约」的条数 */
    private static final int RECENT_BOOKING_LIMIT = 10;

    private final SysUserMapper userMapper;
    private final BookingMapper bookingMapper;
    private final NotificationService notificationService;

    /**
     * 分页查询用户（含违约/封禁信息）。
     *
     * <p>返回 {@link UserListVO} 而不是实体：实体里的 openid 对管理端毫无用途，
     * 却是定位某个真实微信用户的钥匙，没必要下发到浏览器。</p>
     */
    public Page<UserListVO> page(int page, int size, String keyword) {
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getCreateTime);
        if (keyword != null && !keyword.trim().isEmpty()) {
            // 转义 LIKE 通配符：否则输入一个 % 就等于把全表捞出来
            String kw = escapeLike(keyword.trim());
            wrapper.and(w -> w.like(SysUser::getNickname, kw)
                    .or().like(SysUser::getPhone, kw));
        }
        Page<SysUser> source = userMapper.selectPage(new Page<>(page, size), wrapper);
        Page<UserListVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        List<UserListVO> records = new ArrayList<>();
        for (SysUser u : source.getRecords()) {
            records.add(toListVO(u));
        }
        result.setRecords(records);
        return result;
    }

    /**
     * 封禁用户。
     *
     * <p>两种语义：</p>
     * <ul>
     *   <li>限时封禁（1/7/30 天）：账号保持可登录，仅暂停预约权限，到期自动恢复；</li>
     *   <li>永久禁用（days 为空或 &lt;= 0）：写 status=0 + permanentBan=1，登录直接被拒绝。</li>
     * </ul>
     *
     * <p><b>永久禁用与限时封禁互不覆盖</b>：早期实现两者都只改 status，
     * 于是「给永久禁用用户设 1 天封禁」会把 status 改回 1，等于顺手把人解禁；
     * 解封接口同理。现在永久禁用由 {@code permanent_ban} 独立标记，
     * 限时封禁/解封遇到它直接拒绝，必须显式走 {@link #enable(Long)}。</p>
     *
     * <p>封禁后强制踢下线，否则已登录的 token 在 30 天有效期内仍可访问全部用户接口。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void ban(Long userId, Integer days) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        boolean permanent = days == null || days <= 0;
        if (!permanent && days != 1 && days != 7 && days != 30) {
            throw new BusinessException(400, "封禁天数仅支持 1/7/30 天或永久");
        }
        boolean alreadyPermanent = user.getPermanentBan() != null && user.getPermanentBan() == 1;
        if (alreadyPermanent && !permanent) {
            throw new BusinessException(400,
                    "该用户已被永久禁用，不能降级为限时封禁；如需恢复请先执行「解除禁用」");
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                // 来源固定为「管理员人工」，与违约自动封禁区分，提示文案才不会张冠李戴
                .set(SysUser::getBanSource, BanSource.MANUAL)
                .set(SysUser::getUpdateTime, now);

        String notice;
        if (permanent) {
            wrapper.set(SysUser::getStatus, 0)
                    .set(SysUser::getPermanentBan, 1)
                    .set(SysUser::getBannedUntil, null);
            notice = "您的账号已被管理员禁用，无法继续使用，如有疑问请联系管理员。";
        } else {
            LocalDateTime until = now.plusDays(days);
            wrapper.set(SysUser::getStatus, 1)
                    .set(SysUser::getPermanentBan, 0)
                    .set(SysUser::getBannedUntil, until);
            notice = "您的预约权限已被管理员暂停至 " + until.format(DT_FMT) + "，到期后自动恢复。";
        }
        userMapper.update(null, wrapper);

        kickout(userId);
        notificationService.push(userId, 3, "预约权限受限", notice, null);
        log.info("管理员封禁用户 {}，类型：{}，来源：人工", userId, permanent ? "永久" : days + "天");
    }

    /**
     * 解封限时封禁：清除封禁截止时间与违约计数，并恢复账号状态。
     *
     * <p>注意必须用 UpdateWrapper 显式 set null：MyBatis-Plus 默认 NOT_NULL 更新策略下，
     * {@code setBannedUntil(null) + updateById()} 会被跳过，banned_until 根本不会被清空。</p>
     *
     * <p>永久禁用不走这里：解封只处理「限时封禁」，遇到永久禁用直接拒绝并提示改用「解除禁用」，
     * 避免误点一次就把禁用账号放回来。</p>
     */
    public void unban(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (user.getPermanentBan() != null && user.getPermanentBan() == 1) {
            throw new BusinessException(400,
                    "该用户处于永久禁用状态，请使用「解除禁用」操作（解封仅针对限时封禁）");
        }
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getBannedUntil, null)
                .set(SysUser::getBanSource, null)
                .set(SysUser::getStatus, 1)
                .set(SysUser::getViolationCount, 0)
                .set(SysUser::getUpdateTime, LocalDateTime.now()));
        log.info("管理员解封用户 {}", userId);
    }

    /**
     * 解除永久禁用：把账号恢复为可登录、可预约的正常状态。
     *
     * <p>与 {@link #unban(Long)} 分开是刻意的：永久禁用是「账号级」处置，
     * 解封是「预约权限级」处置，语义不同就不该共用一个按钮。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void enable(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (user.getPermanentBan() == null || user.getPermanentBan() != 1) {
            throw new BusinessException(400, "该用户当前不是永久禁用状态");
        }
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPermanentBan, 0)
                .set(SysUser::getStatus, 1)
                .set(SysUser::getBannedUntil, null)
                .set(SysUser::getBanSource, null)
                .set(SysUser::getViolationCount, 0)
                .set(SysUser::getUpdateTime, LocalDateTime.now()));
        notificationService.push(userId, 3, "账号已恢复",
                "您的账号已由管理员解除禁用，可以正常使用会议室预约功能。", null);
        log.info("管理员解除用户 {} 的永久禁用", userId);
    }

    /**
     * 用户详情：基础资料 + 预约统计 + 最近预约
     */
    public UserDetailVO detail(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        LocalDateTime now = LocalDateTime.now();

        UserDetailVO vo = new UserDetailVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setViolationCount(user.getViolationCount() == null ? 0 : user.getViolationCount());
        vo.setBannedUntil(user.getBannedUntil());
        vo.setBanSource(user.getBanSource());
        vo.setPermanentBan(user.getPermanentBan() == null ? 0 : user.getPermanentBan());
        vo.setCreateTime(user.getCreateTime());

        boolean disabled = user.getStatus() != null && user.getStatus() == 0;
        boolean timeBanned = user.getBannedUntil() != null && user.getBannedUntil().isAfter(now);
        // status 优先：永久禁用即使残留 bannedUntil，也按永久展示，避免前后台状态不一致
        vo.setBanned(disabled || timeBanned);
        vo.setBanType(disabled ? "permanent" : (timeBanned ? "temporary" : null));

        Map<String, Object> stat = bookingMapper.statByUser(userId);
        vo.setTotal(statLong(stat, "total"));
        vo.setPending(statLong(stat, "pending"));
        vo.setApproved(statLong(stat, "approved"));
        vo.setRejected(statLong(stat, "rejected"));
        vo.setCanceled(statLong(stat, "canceled"));
        vo.setViolated(statLong(stat, "violated"));
        vo.setUpcoming(bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .in(Booking::getStatus, BookingStatus.PENDING, BookingStatus.APPROVED)
                .gt(Booking::getEndTime, now)));
        vo.setRecentBookings(bookingMapper.listRecentByUser(userId, RECENT_BOOKING_LIMIT));
        return vo;
    }

    /**
     * 查询用户（供通知等服务获取 openid 等）
     */
    public SysUser getById(Long userId) {
        return userMapper.selectById(userId);
    }

    // ===== 内部方法 =====

    private UserListVO toListVO(SysUser u) {
        UserListVO vo = new UserListVO();
        vo.setId(u.getId());
        vo.setNickname(u.getNickname());
        vo.setPhone(u.getPhone());
        vo.setAvatar(u.getAvatar());
        vo.setStatus(u.getStatus());
        vo.setViolationCount(u.getViolationCount() == null ? 0 : u.getViolationCount());
        vo.setBannedUntil(u.getBannedUntil());
        vo.setBanSource(u.getBanSource());
        vo.setPermanentBan(u.getPermanentBan() == null ? 0 : u.getPermanentBan());
        vo.setCreateTime(u.getCreateTime());
        return vo;
    }

    private String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 强制下线：封禁后立即失效该用户的全部 token。
     *
     * <p>踢下线失败不影响封禁结果，因此这里吞掉异常只记日志。</p>
     */
    private void kickout(Long userId) {
        try {
            StpUtil.kickout(LoginHelper.USER_PREFIX + userId);
        } catch (Exception e) {
            log.warn("踢下线失败 userId={}", userId, e);
        }
    }

    /** MySQL 的 COUNT 返回 Long、SUM 返回 BigDecimal 或 null，统一转成 long */
    private long statLong(Map<String, Object> stat, String key) {
        if (stat == null) {
            return 0L;
        }
        Object value = stat.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
