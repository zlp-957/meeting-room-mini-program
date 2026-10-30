package com.meeting.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.meeting.common.BusinessException;
import com.meeting.common.LoginHelper;
import com.meeting.common.PasswordUtil;
import com.meeting.dto.AdminLoginDTO;
import com.meeting.dto.BindPhoneDTO;
import com.meeting.dto.UpdateNicknameDTO;
import com.meeting.dto.WxLoginDTO;
import com.meeting.entity.SysAdmin;
import com.meeting.entity.SysUser;
import com.meeting.mapper.SysAdminMapper;
import com.meeting.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 图形验证码有效期：5 分钟（P2-2） */
    private static final long CAPTCHA_TTL_MILLIS = 5 * 60 * 1000L;
    /** 验证码池上限，避免无上限占用内存（P2-2） */
    private static final int CAPTCHA_MAX_SIZE = 5000;
    /** 管理员登录失败锁定：连续 5 次失败锁定 10 分钟（防暴力猜密码） */
    private static final int LOGIN_MAX_FAILS = 5;
    private static final long LOGIN_LOCK_MILLIS = 10 * 60 * 1000L;
    private static final int LOGIN_FAIL_STORE_MAX = 10000;
    /** 去掉容易混淆的 0/O/1/I/L */
    private static final char[] CAPTCHA_CHARS = "ABCDEFGHJKMNPQRSTUVWXY23456789".toCharArray();
    private final SysUserMapper userMapper;
    private final SysAdminMapper adminMapper;
    private final WechatService wechatService;
    private final NicknameCheckService nicknameCheckService;
    private final ContentSecurityService contentSecurityService;
    private final FileStorageService fileStorageService;
    /**
     * 验证码调试开关（默认关闭）
     *
     * <p>仅用于本地联调 / 自动化测试：开启时接口会额外返回 captchaText 明文。
     * 生产环境必须保持 false，否则图形验证码形同虚设。</p>
     */
    @Value("${meeting.captcha-debug:false}")
    private boolean captchaDebug;

    private final SecureRandom random = new SecureRandom();

    // 图形验证码存储（开发阶段用内存，生产建议 Redis）
    private final Map<String, Captcha> captchaStore = new ConcurrentHashMap<>();

    // 登录失败计数（生产建议 Redis）
    private final Map<String, LoginFail> loginFailStore = new ConcurrentHashMap<>();

    /**
     * 用户微信登录
     */
    public Map<String, Object> wxLogin(WxLoginDTO dto) {
        String openid = wechatService.getOpenid(dto.getCode());
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getOpenid, openid));

        if (user == null) {
            // 首次登录，注册新用户
            user = new SysUser();
            user.setOpenid(openid);
            user.setStatus(1);
            userMapper.insert(user);
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(400, "账号已被禁用");
        }

        LoginHelper.loginAsUser(user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("userInfo", buildUserInfo(user));
        result.put("needBindPhone", user.getPhone() == null);
        return result;
    }

    /**
     * 绑定 / 修改手机号（仅填写，不做短信验证）。
     *
     * <p><b>为什么可以不做验证</b>：本项目的手机号只是资料字段与联系方式，
     * <b>不作为登录凭据</b>（登录只走微信授权）。因此「抢占他人手机号」的后果仅限于
     * 占住一个号码的展示位，无法借此登录别人的账号 —— 这正是去掉验证码的前提。</p>
     *
     * <p><b>不要同时开启手机号登录</b>：一旦手机号能用于登录，「谁先绑定谁拥有」就会变成
     * 完整的账号接管链路（绑他人号码 → 用该号码登录）。原短信登录入口已随验证码一并下线。</p>
     *
     * <p>手机号全局唯一：数据库层有 uk_phone 唯一索引兜底，这里先做业务校验给出友好提示。</p>
     */
    public Map<String, Object> bindPhone(BindPhoneDTO dto) {
        Long userId = LoginHelper.userId();
        // 手动输入手机号，去掉首尾空格后落库（DTO 已 @NotBlank + @Pattern 校验格式）
        String phone = dto.getPhone().trim();

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        SysUser exists = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
        if (exists != null && !exists.getId().equals(userId)) {
            throw new BusinessException(409, "该手机号已绑定其他微信账号");
        }

        user.setPhone(phone);
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException e) {
            // 并发绑定同一手机号时由唯一索引兜底
            throw new BusinessException(409, "该手机号已绑定其他微信账号");
        }

        return buildUserInfo(user);
    }

    /**
     * 修改昵称
     *
     * <p>内容合规由 {@link NicknameCheckService} 负责：长度、字符集、敏感词、冒充官方、广告引流。</p>
     */
    public Map<String, Object> updateNickname(UpdateNicknameDTO dto) {
        Long userId = LoginHelper.userId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        String nickname = nicknameCheckService.check(dto.getNickname());
        // 本地词库通过后，再叠一层微信官方内容安全检测（不可用时自动降级放行）
        contentSecurityService.assertTextSafe(nickname, user.getOpenid());
        user.setNickname(nickname);
        userMapper.updateById(user);
        return buildUserInfo(user);
    }

    /**
     * 更新头像（图片已由 {@link FileStorageService} 落盘，这里只存相对路径）
     */
    public Map<String, Object> updateAvatar(String avatarUrl) {
        Long userId = LoginHelper.userId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        String old = user.getAvatar();
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);

        // 换头像后清掉旧文件，避免 upload 目录无限增长
        if (old != null && !old.equals(avatarUrl)) {
            fileStorageService.deleteQuietly(old);
        }
        return buildUserInfo(user);
    }

    /**
     * 获取当前登录用户信息（“我的”页进入时刷新，保证换设备后头像昵称同步）
     */
    public Map<String, Object> getProfile() {
        SysUser user = userMapper.selectById(LoginHelper.userId());
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return buildUserInfo(user);
    }

    /**
     * 生成图形验证码（P2-2：不再返回明文，改为图片 + 过期时间）
     */
    public Map<String, String> getCaptcha() {
        evictExpiredCaptcha();

        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CAPTCHA_CHARS[random.nextInt(CAPTCHA_CHARS.length)]);
        }
        String code = sb.toString();

        String key = UUID.randomUUID().toString().replace("-", "");
        captchaStore.put(key, new Captcha(code, System.currentTimeMillis() + CAPTCHA_TTL_MILLIS));

        Map<String, String> result = new HashMap<>();
        result.put("captchaKey", key);
        result.put("captchaImage", renderCaptchaImage(code));
        if (captchaDebug) {
            result.put("captchaText", code);
        }
        return result;
    }

    /**
     * 管理员登录
     */
    public Map<String, Object> adminLogin(AdminLoginDTO dto) {
        String username = dto.getUsername() == null ? "" : dto.getUsername().trim();
        // 先判断是否已因连续失败被锁定，避免继续消耗验证码
        assertLoginAllowed(username);

        // 校验验证码（key 缺失时不能让 ConcurrentHashMap.remove(null) 抛 NPE）
        if (dto.getCaptchaKey() == null || dto.getCaptchaKey().trim().isEmpty()) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        Captcha captcha = captchaStore.remove(dto.getCaptchaKey());
        if (captcha == null
                || System.currentTimeMillis() > captcha.expireAt
                || !captcha.code.equalsIgnoreCase(dto.getCaptcha())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }

        SysAdmin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<SysAdmin>().eq(SysAdmin::getUsername, username));
        if (admin == null) {
            recordLoginFail(username);
            throw new BusinessException(400, "用户名或密码错误");
        }

        // P2-3：PBKDF2 校验，同时兼容历史 MD5 数据
        if (dto.getPassword() == null || !PasswordUtil.matches(dto.getPassword(), admin.getPassword())) {
            recordLoginFail(username);
            throw new BusinessException(400, "用户名或密码错误");
        }

        if (admin.getStatus() != null && admin.getStatus() == 0) {
            throw new BusinessException(400, "账号已被禁用");
        }

        // 历史 MD5 密码在登录成功后自动升级为 PBKDF2
        if (PasswordUtil.isLegacy(admin.getPassword())) {
            admin.setPassword(PasswordUtil.encode(dto.getPassword()));
            adminMapper.updateById(admin);
        }

        clearLoginFail(username);
        LoginHelper.loginAsAdmin(admin.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("token", StpUtil.getTokenValue());
        Map<String, Object> info = new HashMap<>();
        info.put("id", admin.getId());
        info.put("username", admin.getUsername());
        info.put("realName", admin.getRealName());
        info.put("role", admin.getRole());
        result.put("adminInfo", info);
        return result;
    }

    /**
     * 退出登录
     */
    public void logout() {
        StpUtil.logout();
    }

    // ===== 内部方法 =====

    /**
     * 登录前检查是否处于锁定期
     */
    private void assertLoginAllowed(String username) {
        LoginFail fail = loginFailStore.get(username);
        if (fail == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (fail.lockUntil > now) {
            long minutes = Math.max(1, (fail.lockUntil - now + 59_999L) / 60_000L);
            throw new BusinessException(429, "登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
        // 锁定期已过，清掉旧记录
        if (fail.lockUntil > 0) {
            loginFailStore.remove(username);
        }
    }

    /**
     * 记录一次登录失败，达到阈值后锁定
     */
    private void recordLoginFail(String username) {
        LoginFail fail = loginFailStore.computeIfAbsent(username, k -> new LoginFail());
        synchronized (fail) {
            fail.count++;
            if (fail.count >= LOGIN_MAX_FAILS) {
                fail.lockUntil = System.currentTimeMillis() + LOGIN_LOCK_MILLIS;
                fail.count = 0;
            }
        }
        // 防止恶意用户名把内存打爆
        if (loginFailStore.size() > LOGIN_FAIL_STORE_MAX) {
            loginFailStore.clear();
        }
    }

    private void clearLoginFail(String username) {
        loginFailStore.remove(username);
    }

    private void evictExpiredCaptcha() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Captcha>> it = captchaStore.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt < now) {
                it.remove();
            }
        }
        // 池满时直接清空，防止内存无限增长
        if (captchaStore.size() > CAPTCHA_MAX_SIZE) {
            captchaStore.clear();
        }
    }

    /**
     * 渲染 4 位字母数字验证码图片（PNG Base64）
     */
    private String renderCaptchaImage(String code) {
        int width = 120;
        int height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(237, 242, 255));
            g.fillRect(0, 0, width, height);

            // 干扰线
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(170 + random.nextInt(60), 190 + random.nextInt(50), 225 + random.nextInt(30)));
                g.drawLine(random.nextInt(width), random.nextInt(height),
                        random.nextInt(width), random.nextInt(height));
            }

            g.setFont(new Font("Arial", Font.BOLD, 26));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(20 + random.nextInt(50), 50 + random.nextInt(60), 150 + random.nextInt(80)));
                int x = 14 + i * 25;
                int y = 30;
                double theta = (random.nextInt(30) - 15) * Math.PI / 180;
                g.rotate(theta, x, height / 2.0);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.rotate(-theta, x, height / 2.0);
            }
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new BusinessException(500, "验证码生成失败");
        }
    }

    private Map<String, Object> buildUserInfo(SysUser user) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", user.getId());
        info.put("nickname", user.getNickname());
        info.put("avatar", user.getAvatar());
        info.put("phone", user.getPhone());
        return info;
    }

    /** 验证码缓存项 */
    private static class Captcha {
        private final String code;
        private final long expireAt;

        private Captcha(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }

    /** 登录失败计数项 */
    private static class LoginFail {
        private int count;
        private long lockUntil;
    }
}
